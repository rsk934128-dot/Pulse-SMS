package com.example.telephony

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class FileRootInfo(
    val id: String,
    val name: String,
    val rootType: String, // "SAF_FOLDER", "SHARED_PINS", "APP_FILES"
    val uriString: String? = null,
    val isWritable: Boolean = true,
    val subtitle: String = ""
)

data class FileBreadcrumbItem(
    val name: String,
    val path: String
)

data class DeviceFileEntry(
    val id: String,
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val modifiedMs: Long,
    val category: String, // "FOLDER", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "ARCHIVE", "OTHER"
    val isDeletable: Boolean = true
)

data class DirectoryBrowseResult(
    val status: String,
    val hasFolderAccess: Boolean,
    val roots: List<FileRootInfo>,
    val currentRootId: String,
    val currentRootName: String,
    val currentPath: String,
    val breadcrumbs: List<FileBreadcrumbItem>,
    val entries: List<DeviceFileEntry>,
    val totalFolders: Int,
    val totalFiles: Int,
    val totalBytes: Long,
    val rootsJson: String,
    val breadcrumbsJson: String,
    val entriesJson: String,
    val errorMessage: String? = null
)

data class FileTransferPayloadResult(
    val status: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64Data: String? = null,
    val isPreviewImage: Boolean = false,
    val errorMessage: String? = null
)

data class WebUploadSaveResult(
    val success: Boolean,
    val savedName: String,
    val message: String
)

/**
 * Play-Policy Compliant File Manager Bridge.
 * Uses Android Storage Access Framework (OpenDocumentTree / OpenMultipleDocuments),
 * Photo Picker, and app-local workspace folders — zero broad storage permissions required.
 */
object DeviceFileManagerHelper {

    private const val TAG = "DeviceFileManagerHelper"
    private const val PREFS_NAME = "pulse_file_manager_prefs"
    private const val KEY_SAF_ROOTS_JSON = "saf_tree_roots_json"
    private const val KEY_PINNED_URIS_JSON = "pinned_file_uris_json"

    const val ROOT_APP_FILES = "app_files"
    const val ROOT_SHARED_PINS = "shared_pins"

    private const val MAX_ENTRIES_PER_SYNC = 120
    private const val MAX_RAW_TRANSFER_BYTES = 520_000L

    private val INTERNAL_HIDDEN_PREFIXES = setOf(
        "datastore",
        "generatefid",
        "PersistedInstallation",
        "com.google.",
        "profileInstalled",
        "rlist"
    )

    fun hasCustomFolderAccess(context: Context): Boolean {
        return loadSafRoots(context).isNotEmpty()
    }

    fun addGrantedFolderUri(context: Context, uri: Uri): FileRootInfo? {
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "Failed persistable read permission for $uri", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission error: ${e.message}")
        }

        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            uri.lastPathSegment ?: "folder"
        }
        val folderName = queryDocumentDisplayName(
            resolver = resolver,
            docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        ) ?: deriveNiceFolderName(docId)

        val stableId = "saf_" + abs(uri.toString().hashCode()).toString(36)
        val info = FileRootInfo(
            id = stableId,
            name = folderName,
            rootType = "SAF_FOLDER",
            uriString = uri.toString(),
            isWritable = true,
            subtitle = docId.take(48)
        )

        val current = loadSafRoots(appContext).filterNot { it.uriString == uri.toString() || it.id == stableId }.toMutableList()
        current.add(0, info)
        saveSafRoots(appContext, current)
        return info
    }

    fun removeGrantedRoot(context: Context, rootId: String) {
        val appContext = context.applicationContext ?: context
        val current = loadSafRoots(appContext)
        val target = current.firstOrNull { it.id == rootId }
        if (target?.uriString != null) {
            try {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(target.uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Throwable) {}
        }
        saveSafRoots(appContext, current.filterNot { it.id == rootId })
    }

    fun addPinnedFiles(context: Context, uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver
        val existing = loadPinnedUriStrings(appContext).toMutableList()
        var added = 0
        uris.forEach { uri ->
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {}
            val str = uri.toString()
            if (str !in existing) {
                existing.add(0, str)
                added++
            }
        }
        savePinnedUriStrings(appContext, existing.take(100))
        return added
    }

    fun getConfiguredRoots(context: Context): List<FileRootInfo> {
        val appContext = context.applicationContext ?: context
        val safRoots = loadSafRoots(appContext)
        val pinnedCount = loadPinnedUriStrings(appContext).size
        val list = mutableListOf<FileRootInfo>()
        list.addAll(safRoots)
        if (pinnedCount > 0) {
            list.add(
                FileRootInfo(
                    id = ROOT_SHARED_PINS,
                    name = "পিন করা ফাইল ($pinnedCount)",
                    rootType = "SHARED_PINS",
                    uriString = null,
                    isWritable = false,
                    subtitle = "Quick Shared Media & Docs"
                )
            )
        }
        list.add(
            FileRootInfo(
                id = ROOT_APP_FILES,
                name = "Pulse অ্যাপ ফাইল ও রেকর্ডিং",
                rootType = "APP_FILES",
                uriString = null,
                isWritable = true,
                subtitle = "App Workspace & Web Received"
            )
        )
        return list
    }

    suspend fun browseDirectory(
        context: Context,
        targetRootId: String? = null,
        targetPath: String? = null
    ): DirectoryBrowseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        ensureAppWorkspaceFolders(appContext)

        val roots = getConfiguredRoots(appContext)
        val hasCustom = roots.any { it.rootType == "SAF_FOLDER" }
        val resolvedRoot = roots.firstOrNull { it.id == targetRootId }
            ?: roots.firstOrNull { it.rootType == "SAF_FOLDER" }
            ?: roots.first()

        val cleanPath = normalizeRelativePath(targetPath)

        try {
            val (entries, totalFolders, totalFiles, totalBytes, effectivePath) = when (resolvedRoot.rootType) {
                "SAF_FOLDER" -> browseSafTreeDirectory(appContext, resolvedRoot, cleanPath)
                "SHARED_PINS" -> browsePinnedFiles(appContext)
                else -> browseAppFilesDirectory(appContext, cleanPath)
            }

            val breadcrumbs = buildBreadcrumbs(resolvedRoot.name, effectivePath)
            val rootsJson = serializeRootsJson(roots)
            val breadcrumbsJson = serializeBreadcrumbsJson(breadcrumbs)
            val entriesJson = serializeEntriesJson(entries)

            DirectoryBrowseResult(
                status = "READY",
                hasFolderAccess = hasCustom,
                roots = roots,
                currentRootId = resolvedRoot.id,
                currentRootName = resolvedRoot.name,
                currentPath = effectivePath,
                breadcrumbs = breadcrumbs,
                entries = entries,
                totalFolders = totalFolders,
                totalFiles = totalFiles,
                totalBytes = totalBytes,
                rootsJson = rootsJson,
                breadcrumbsJson = breadcrumbsJson,
                entriesJson = entriesJson
            )
        } catch (e: Exception) {
            Log.w(TAG, "browseDirectory error: ${e.message}", e)
            val breadcrumbs = buildBreadcrumbs(resolvedRoot.name, "")
            DirectoryBrowseResult(
                status = "ERROR",
                hasFolderAccess = hasCustom,
                roots = roots,
                currentRootId = resolvedRoot.id,
                currentRootName = resolvedRoot.name,
                currentPath = "",
                breadcrumbs = breadcrumbs,
                entries = emptyList(),
                totalFolders = 0,
                totalFiles = 0,
                totalBytes = 0L,
                rootsJson = serializeRootsJson(roots),
                breadcrumbsJson = serializeBreadcrumbsJson(breadcrumbs),
                entriesJson = "[]",
                errorMessage = e.message?.take(250) ?: "ফোল্ডার লোড করা যায়নি"
            )
        }
    }

    suspend fun prepareFileForWeb(
        context: Context,
        rootId: String?,
        fileId: String
    ): FileTransferPayloadResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val roots = getConfiguredRoots(appContext)
        val root = roots.firstOrNull { it.id == rootId }
            ?: roots.firstOrNull { it.rootType == "APP_FILES" }
            ?: return@withContext FileTransferPayloadResult("ERROR", "file", "application/octet-stream", 0L, null, false, "রুট ফোল্ডার পাওয়া যায়নি")

        try {
            when (root.rootType) {
                "APP_FILES" -> {
                    val base = appContext.filesDir.canonicalFile
                    val rel = normalizeRelativePath(fileId)
                    val targetFile = File(base, rel).canonicalFile
                    if (!targetFile.path.startsWith(base.path) || !targetFile.exists() || !targetFile.isFile) {
                        return@withContext FileTransferPayloadResult("ERROR", rel.substringAfterLast('/'), "application/octet-stream", 0L, null, false, "ফাইলটি পাওয়া যায়নি")
                    }
                    val mime = guessMimeType(targetFile.name, null)
                    encodeStreamToPayload(
                        fileName = targetFile.name,
                        mimeType = mime,
                        knownSize = targetFile.length(),
                        openStream = { targetFile.inputStream() }
                    )
                }
                "SHARED_PINS" -> {
                    val uri = loadPinnedUriStrings(appContext).firstOrNull { uriHashId(it) == fileId || it == fileId }
                        ?.let { Uri.parse(it) }
                        ?: return@withContext FileTransferPayloadResult("ERROR", "file", "application/octet-stream", 0L, null, false, "পিন করা ফাইল পাওয়া যায়নি")
                    val meta = queryGenericUriMetadata(appContext.contentResolver, uri)
                    val mime = meta.mimeType.ifBlank { guessMimeType(meta.name, null) }
                    encodeStreamToPayload(
                        fileName = meta.name,
                        mimeType = mime,
                        knownSize = meta.sizeBytes,
                        openStream = { appContext.contentResolver.openInputStream(uri) }
                    )
                }
                else -> {
                    val treeUri = Uri.parse(root.uriString ?: return@withContext FileTransferPayloadResult("ERROR", "file", "application/octet-stream", 0L, null, false, "ফোল্ডার URI পাওয়া যায়নি"))
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileId)
                    val meta = queryGenericUriMetadata(appContext.contentResolver, docUri)
                    val mime = meta.mimeType.ifBlank { guessMimeType(meta.name, null) }
                    encodeStreamToPayload(
                        fileName = meta.name,
                        mimeType = mime,
                        knownSize = meta.sizeBytes,
                        openStream = { appContext.contentResolver.openInputStream(docUri) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareFileForWeb error: ${e.message}", e)
            FileTransferPayloadResult(
                status = "ERROR",
                fileName = fileId.substringAfterLast('/'),
                mimeType = "application/octet-stream",
                sizeBytes = 0L,
                base64Data = null,
                isPreviewImage = false,
                errorMessage = e.message?.take(250) ?: "ফাইল পড়া যায়নি"
            )
        }
    }

    suspend fun saveWebUploadedFile(
        context: Context,
        targetRootId: String?,
        targetPath: String?,
        fileName: String,
        mimeType: String,
        base64Data: String
    ): WebUploadSaveResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val safeName = sanitizeFileName(fileName)
        val safeMime = mimeType.ifBlank { guessMimeType(safeName, "application/octet-stream") }
        val bytes = try {
            Base64.decode(base64Data, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withContext WebUploadSaveResult(false, safeName, "ফাইল ডাটা ডিকোড করা যায়নি")
        }

        val roots = getConfiguredRoots(appContext)
        val chosenRoot = roots.firstOrNull { it.id == targetRootId && it.isWritable }
            ?: roots.firstOrNull { it.rootType == "SAF_FOLDER" && it.isWritable }

        if (chosenRoot?.rootType == "SAF_FOLDER" && !chosenRoot.uriString.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(chosenRoot.uriString)
                val parentDocId = resolveSafDirectoryDocId(appContext.contentResolver, treeUri, normalizeRelativePath(targetPath))
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                val createdUri = DocumentsContract.createDocument(appContext.contentResolver, parentDocUri, safeMime, safeName)
                if (createdUri != null) {
                    appContext.contentResolver.openOutputStream(createdUri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    return@withContext WebUploadSaveResult(true, safeName, "ফোনের ফোল্ডারে সেভ হয়েছে: $safeName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SAF upload save fallback to app files: ${e.message}")
            }
        }

        return@withContext try {
            val baseDir = appContext.filesDir.canonicalFile
            val cleanSub = normalizeRelativePath(targetPath).ifBlank { "web_received" }
            val targetDir = File(baseDir, cleanSub).canonicalFile
            if (!targetDir.path.startsWith(baseDir.path)) {
                return@withContext WebUploadSaveResult(false, safeName, "অবৈধ ফোল্ডার পাথ")
            }
            targetDir.mkdirs()
            val outFile = File(targetDir, safeName)
            outFile.writeBytes(bytes)
            WebUploadSaveResult(true, safeName, "ফোনে সেভ হয়েছে: $safeName")
        } catch (e: Exception) {
            WebUploadSaveResult(false, safeName, "সেভ ব্যর্থ: ${e.message}")
        }
    }

    suspend fun deleteFileEntry(
        context: Context,
        rootId: String?,
        fileId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val roots = getConfiguredRoots(appContext)
        val root = roots.firstOrNull { it.id == rootId } ?: return@withContext false

        return@withContext try {
            when (root.rootType) {
                "APP_FILES" -> {
                    val base = appContext.filesDir.canonicalFile
                    val target = File(base, normalizeRelativePath(fileId)).canonicalFile
                    if (target.path.startsWith(base.path) && target != base && target.exists()) {
                        target.deleteRecursively()
                    } else false
                }
                "SHARED_PINS" -> {
                    val remaining = loadPinnedUriStrings(appContext).filterNot { uriHashId(it) == fileId || it == fileId }
                    savePinnedUriStrings(appContext, remaining)
                    true
                }
                else -> {
                    val treeUri = Uri.parse(root.uriString ?: return@withContext false)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileId)
                    DocumentsContract.deleteDocument(appContext.contentResolver, docUri)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteFileEntry error: ${e.message}")
            false
        }
    }

    suspend fun createSubfolder(
        context: Context,
        rootId: String?,
        targetPath: String?,
        folderName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val safeFolder = sanitizeFileName(folderName)
        if (safeFolder.isBlank()) return@withContext false

        val roots = getConfiguredRoots(appContext)
        val root = roots.firstOrNull { it.id == rootId } ?: return@withContext false
        val cleanPath = normalizeRelativePath(targetPath)

        return@withContext try {
            when (root.rootType) {
                "APP_FILES" -> {
                    val base = appContext.filesDir.canonicalFile
                    val parent = if (cleanPath.isBlank()) base else File(base, cleanPath).canonicalFile
                    if (!parent.path.startsWith(base.path)) return@withContext false
                    File(parent, safeFolder).mkdirs()
                }
                "SAF_FOLDER" -> {
                    val treeUri = Uri.parse(root.uriString ?: return@withContext false)
                    val parentDocId = resolveSafDirectoryDocId(appContext.contentResolver, treeUri, cleanPath)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
                    DocumentsContract.createDocument(
                        appContext.contentResolver,
                        parentDocUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        safeFolder
                    ) != null
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "createSubfolder error: ${e.message}")
            false
        }
    }

    fun getShareableFileUri(context: Context, rootId: String, entry: DeviceFileEntry): Uri? {
        val appContext = context.applicationContext ?: context
        val roots = getConfiguredRoots(appContext)
        val root = roots.firstOrNull { it.id == rootId } ?: return null
        return when (root.rootType) {
            "SAF_FOLDER" -> {
                val treeUri = root.uriString?.let { Uri.parse(it) } ?: return null
                DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
            }
            "SHARED_PINS" -> {
                loadPinnedUriStrings(appContext).firstOrNull { uriHashId(it) == entry.id || it == entry.id }
                    ?.let { Uri.parse(it) }
            }
            else -> {
                val base = appContext.filesDir.canonicalFile
                val file = File(base, normalizeRelativePath(entry.id)).canonicalFile
                if (!file.path.startsWith(base.path) || !file.exists()) return null
                try {
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal Directory Traversal Helpers
    // -------------------------------------------------------------------------

    private data class TraversalTuple(
        val entries: List<DeviceFileEntry>,
        val totalFolders: Int,
        val totalFiles: Int,
        val totalBytes: Long,
        val effectivePath: String
    )

    private fun browseSafTreeDirectory(
        context: Context,
        root: FileRootInfo,
        relativePath: String
    ): TraversalTuple {
        val treeUri = Uri.parse(root.uriString!!)
        val resolver = context.contentResolver
        val targetDocId = resolveSafDirectoryDocId(resolver, treeUri, relativePath)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val allEntries = mutableListOf<DeviceFileEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idxId = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idxSize = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val idxMod = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val idxFlags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

            while (cursor.moveToNext()) {
                val docId = if (idxId >= 0) cursor.getString(idxId) ?: continue else continue
                val name = if (idxName >= 0) cursor.getString(idxName) ?: "Unnamed" else "Unnamed"
                val mime = if (idxMime >= 0) cursor.getString(idxMime) ?: "" else ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (idxSize >= 0 && !cursor.isNull(idxSize)) cursor.getLong(idxSize).coerceAtLeast(0L) else 0L
                val modMs = if (idxMod >= 0 && !cursor.isNull(idxMod)) cursor.getLong(idxMod).coerceAtLeast(0L) else 0L
                val flags = if (idxFlags >= 0 && !cursor.isNull(idxFlags)) cursor.getInt(idxFlags) else 0
                val deletable = (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0

                val entryPath = if (relativePath.isBlank()) name else "$relativePath/$name"
                val resolvedMime = if (isDir) mime else mime.ifBlank { guessMimeType(name, "application/octet-stream") }
                allEntries.add(
                    DeviceFileEntry(
                        id = docId,
                        name = name,
                        path = entryPath,
                        isDir = isDir,
                        sizeBytes = size,
                        mimeType = resolvedMime,
                        modifiedMs = modMs,
                        category = classifyCategory(isDir, resolvedMime, name),
                        isDeletable = deletable
                    )
                )
            }
        }

        val sorted = allEntries.sortedWith(
            compareByDescending<DeviceFileEntry> { it.isDir }
                .thenByDescending { it.modifiedMs }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        val foldersCount = sorted.count { it.isDir }
        val filesCount = sorted.count { !it.isDir }
        val totalBytes = sorted.sumOf { if (it.isDir) 0L else it.sizeBytes }

        return TraversalTuple(
            entries = sorted.take(MAX_ENTRIES_PER_SYNC),
            totalFolders = foldersCount,
            totalFiles = filesCount,
            totalBytes = totalBytes,
            effectivePath = relativePath
        )
    }

    private fun browseAppFilesDirectory(
        context: Context,
        relativePath: String
    ): TraversalTuple {
        val base = context.filesDir.canonicalFile
        val requested = if (relativePath.isBlank()) base else File(base, relativePath).canonicalFile
        val activeDir = if (requested.path.startsWith(base.path) && requested.exists() && requested.isDirectory) {
            requested
        } else {
            base
        }
        val effectivePath = if (activeDir == base) "" else activeDir.relativeTo(base).path.replace(File.separatorChar, '/')

        val children = activeDir.listFiles()?.toList().orEmpty()
            .filter { file ->
                val n = file.name
                !(activeDir == base && INTERNAL_HIDDEN_PREFIXES.any { n.startsWith(it, ignoreCase = true) })
            }

        val entries = children.map { file ->
            val relPath = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val isDir = file.isDirectory
            val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else guessMimeType(file.name, "application/octet-stream")
            DeviceFileEntry(
                id = relPath,
                name = file.name,
                path = relPath,
                isDir = isDir,
                sizeBytes = if (isDir) 0L else file.length().coerceAtLeast(0L),
                mimeType = mime,
                modifiedMs = file.lastModified().coerceAtLeast(0L),
                category = classifyCategory(isDir, mime, file.name),
                isDeletable = true
            )
        }.sortedWith(
            compareByDescending<DeviceFileEntry> { it.isDir }
                .thenByDescending { it.modifiedMs }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )

        return TraversalTuple(
            entries = entries.take(MAX_ENTRIES_PER_SYNC),
            totalFolders = entries.count { it.isDir },
            totalFiles = entries.count { !it.isDir },
            totalBytes = entries.sumOf { if (it.isDir) 0L else it.sizeBytes },
            effectivePath = effectivePath
        )
    }

    private fun browsePinnedFiles(context: Context): TraversalTuple {
        val resolver = context.contentResolver
        val uris = loadPinnedUriStrings(context)
        val entries = mutableListOf<DeviceFileEntry>()
        uris.forEach { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                val meta = queryGenericUriMetadata(resolver, uri)
                val mime = meta.mimeType.ifBlank { guessMimeType(meta.name, "application/octet-stream") }
                entries.add(
                    DeviceFileEntry(
                        id = uriHashId(uriStr),
                        name = meta.name,
                        path = meta.name,
                        isDir = false,
                        sizeBytes = meta.sizeBytes,
                        mimeType = mime,
                        modifiedMs = System.currentTimeMillis(),
                        category = classifyCategory(false, mime, meta.name),
                        isDeletable = true
                    )
                )
            } catch (_: Throwable) {}
        }
        return TraversalTuple(
            entries = entries.take(MAX_ENTRIES_PER_SYNC),
            totalFolders = 0,
            totalFiles = entries.size,
            totalBytes = entries.sumOf { it.sizeBytes },
            effectivePath = ""
        )
    }

    private fun resolveSafDirectoryDocId(
        resolver: ContentResolver,
        treeUri: Uri,
        relativePath: String
    ): String {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        if (relativePath.isBlank()) return currentDocId

        val segments = relativePath.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        for (seg in segments) {
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            var foundNext: String? = null
            resolver.query(
                childUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2) ?: ""
                    val name = cursor.getString(1) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && name == seg) {
                        foundNext = cursor.getString(0)
                        break
                    }
                }
            }
            if (foundNext != null) {
                currentDocId = foundNext!!
            } else {
                break
            }
        }
        return currentDocId
    }

    private fun encodeStreamToPayload(
        fileName: String,
        mimeType: String,
        knownSize: Long,
        openStream: () -> java.io.InputStream?
    ): FileTransferPayloadResult {
        val isImage = mimeType.startsWith("image/", ignoreCase = true)

        if (!isImage && knownSize > MAX_RAW_TRANSFER_BYTES) {
            return FileTransferPayloadResult(
                status = "TOO_LARGE",
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = knownSize,
                base64Data = null,
                isPreviewImage = false,
                errorMessage = "ফাইলটি (${formatFileSize(knownSize)}) ৫০০ KB-এর বড় — ফোনের ফাইল ম্যানেজার থেকে দেখুন বা ছবি প্রিভিউ করুন।"
            )
        }

        val rawBytes = openStream()?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (!isImage && total > MAX_RAW_TRANSFER_BYTES) {
                    return FileTransferPayloadResult(
                        status = "TOO_LARGE",
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = total,
                        base64Data = null,
                        isPreviewImage = false,
                        errorMessage = "ফাইলটি ৫০০ KB-এর বড় হওয়ায় সরাসরি ওয়েবে ট্রান্সফার করা যায়নি।"
                    )
                }
                if (isImage && total > 15_000_000L) {
                    return FileTransferPayloadResult(
                        status = "TOO_LARGE",
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = total,
                        base64Data = null,
                        isPreviewImage = false,
                        errorMessage = "ছবিটি ১৫ MB-এর চেয়ে বড়"
                    )
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: return FileTransferPayloadResult(
            status = "ERROR",
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = knownSize,
            base64Data = null,
            isPreviewImage = false,
            errorMessage = "ফাইল খোলা যায়নি"
        )

        if (isImage && rawBytes.size > MAX_RAW_TRANSFER_BYTES) {
            // Smart compress large phone photos so any image previews & downloads cleanly on web
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            var sample = 1
            while (maxDim / sample > 1440) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return FileTransferPayloadResult("ERROR", fileName, mimeType, rawBytes.size.toLong(), null, false, "ছবি প্রসেস করা যায়নি")

            val compressed = ByteArrayOutputStream()
            var q = 84
            while (q >= 36) {
                compressed.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, compressed)
                if (compressed.size() <= MAX_RAW_TRANSFER_BYTES) break
                q -= 14
            }
            bmp.recycle()
            val jpgBytes = compressed.toByteArray()
            if (jpgBytes.size > MAX_RAW_TRANSFER_BYTES) {
                return FileTransferPayloadResult(
                    status = "TOO_LARGE",
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = rawBytes.size.toLong(),
                    base64Data = null,
                    isPreviewImage = false,
                    errorMessage = "ছবিটি কম্প্রেস করেও ৫০০ KB-এর মধ্যে আনা যায়নি"
                )
            }
            val b64 = Base64.encodeToString(jpgBytes, Base64.NO_WRAP)
            return FileTransferPayloadResult(
                status = "READY",
                fileName = fileName,
                mimeType = "image/jpeg",
                sizeBytes = rawBytes.size.toLong(),
                base64Data = b64,
                isPreviewImage = true
            )
        }

        val b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        return FileTransferPayloadResult(
            status = "READY",
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = rawBytes.size.toLong(),
            base64Data = b64,
            isPreviewImage = isImage
        )
    }

    // -------------------------------------------------------------------------
    // Helpers & Prefs Persistence
    // -------------------------------------------------------------------------

    private data class SimpleUriMeta(val name: String, val sizeBytes: Long, val mimeType: String)

    private fun queryGenericUriMetadata(resolver: ContentResolver, uri: Uri): SimpleUriMeta {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idxName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val idxSize = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idxName >= 0 && !c.isNull(idxName)) {
                        name = c.getString(idxName) ?: name
                    }
                    if (idxSize >= 0 && !c.isNull(idxSize)) {
                        size = c.getLong(idxSize).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Throwable) {}
        val mime = try { resolver.getType(uri) ?: "" } catch (_: Throwable) { "" }
        return SimpleUriMeta(name, size, mime)
    }

    private fun queryDocumentDisplayName(resolver: ContentResolver, docUri: Uri): String? {
        return try {
            resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun deriveNiceFolderName(docId: String): String {
        val tail = docId.substringAfter(':', docId).substringAfterLast('/')
        return tail.ifBlank { "Shared Folder" }
    }

    private fun ensureAppWorkspaceFolders(context: Context) {
        try {
            File(context.filesDir, "web_received").mkdirs()
        } catch (_: Throwable) {}
    }

    private fun normalizeRelativePath(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(180).ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun uriHashId(uriStr: String): String = "pin_" + abs(uriStr.hashCode()).toString(36)

    private fun guessMimeType(name: String, fallback: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            when (ext) {
                "json" -> return "application/json"
                "md" -> return "text/markdown"
                "m4a" -> return "audio/mp4"
                "wav" -> return "audio/wav"
                "apk" -> return "application/vnd.android.package-archive"
                "zip" -> return "application/zip"
            }
        }
        return fallback ?: "application/octet-stream"
    }

    private fun classifyCategory(isDir: Boolean, mimeType: String, name: String): String {
        if (isDir) return "FOLDER"
        val lowerMime = mimeType.lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            lowerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp") -> "IMAGE"
            lowerMime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "3gp", "mov", "avi") -> "VIDEO"
            lowerMime.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "ogg", "aac", "flac") -> "AUDIO"
            ext in setOf("zip", "rar", "7z", "tar", "gz") || lowerMime.contains("zip") || lowerMime.contains("compressed") -> "ARCHIVE"
            lowerMime.startsWith("text/") || lowerMime.contains("pdf") || lowerMime.contains("document") ||
                ext in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "json", "xml", "md", "log") -> "DOCUMENT"
            else -> "OTHER"
        }
    }

    private fun buildBreadcrumbs(rootName: String, path: String): List<FileBreadcrumbItem> {
        val list = mutableListOf(FileBreadcrumbItem(rootName, ""))
        if (path.isNotBlank()) {
            val acc = mutableListOf<String>()
            path.split('/').filter { it.isNotBlank() }.forEach { seg ->
                acc.add(seg)
                list.add(FileBreadcrumbItem(seg, acc.joinToString("/")))
            }
        }
        return list
    }

    private fun serializeRootsJson(roots: List<FileRootInfo>): String {
        val arr = JSONArray()
        roots.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("rootType", r.rootType)
                    put("isWritable", r.isWritable)
                    put("subtitle", r.subtitle)
                }
            )
        }
        return arr.toString()
    }

    private fun serializeBreadcrumbsJson(crumbs: List<FileBreadcrumbItem>): String {
        val arr = JSONArray()
        crumbs.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("path", c.path)
                }
            )
        }
        return arr.toString()
    }

    private fun serializeEntriesJson(entries: List<DeviceFileEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("path", e.path)
                    put("isDir", e.isDir)
                    put("sizeBytes", e.sizeBytes)
                    put("mimeType", e.mimeType)
                    put("modifiedMs", e.modifiedMs)
                    put("category", e.category)
                    put("isDeletable", e.isDeletable)
                }
            )
        }
        return arr.toString()
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun loadSafRoots(context: Context): List<FileRootInfo> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_ROOTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                val uriStr = o.optString("uriString")
                if (id.isBlank() || uriStr.isBlank()) return@mapNotNull null
                FileRootInfo(
                    id = id,
                    name = name.ifBlank { "Folder" },
                    rootType = "SAF_FOLDER",
                    uriString = uriStr,
                    isWritable = o.optBoolean("isWritable", true),
                    subtitle = o.optString("subtitle")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSafRoots(context: Context, roots: List<FileRootInfo>) {
        val arr = JSONArray()
        roots.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("uriString", r.uriString ?: "")
                    put("isWritable", r.isWritable)
                    put("subtitle", r.subtitle)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAF_ROOTS_JSON, arr.toString())
            .apply()
    }

    private fun loadPinnedUriStrings(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PINNED_URIS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePinnedUriStrings(context: Context, uris: List<String>) {
        val arr = JSONArray()
        uris.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PINNED_URIS_JSON, arr.toString())
            .apply()
    }
}
