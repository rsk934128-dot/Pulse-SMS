# Pulse SMS - Android Messaging App 📱

**Pulse SMS** একটি আধুনিক, শক্তিশালী এবং ফিচার-সমৃদ্ধ অ্যান্ড্রয়েড মেসেজিং অ্যাপ যা Jetpack Compose, Kotlin, Room Database, Firebase Cloud Sync এবং Google Gemini 2.5 Flash AI এর সমন্বয়ে তৈরি।

---

## 🌟 প্রধান সুবিধাসমূহ (Key Features)

- **রিয়েল-টাইম এসএমএস ও টেলিফোনি ইঞ্জিন**:
  - ডুয়াল সিম (Dual SIM Slot 1 / Slot 2) সিলেকশন ও সুইচিং।
  - ক্যারেক্টার কাউন্টার (160 characters / SMS parts)।
  - সিস্টেম এসএমএস ইনবক্স রিড ও লাইভ ক্যারিয়ার এসএমএস রিসিভিং (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`)।
  - বিস্তারিত পারমিশন র‍্যাশনালে ডায়ালগ ও সেটিংস নেভিগেশন।

- **স্মার্ট ক্যাটাগোরাইজেশন (Automated Smart Folders)**:
  - 📩 **ব্যক্তিগত (Personal)**: সাধারণ কথোপকথন ও পরিচিত নম্বর।
  - 🔑 **ওটিপি (OTP)**: ব্যাংকিং ও লগইন ওটিপি স্বয়ংক্রিয়ভাবে চিহ্নিতকরণ ও এক ক্লিকে কোড কপি।
  - 💳 **লেনদেন (Transactions)**: bKash, Nagad, কার্ড ও ব্যাংক লেনদেন।
  - 🎁 **অফার ও প্রমোশন (Offers & Promotions)**: টেলিকম ও ব্র্যান্ড অফার আলাদা ফোল্ডারে।

- **Google Gemini AI ইন্টিগ্রেশন (Firebase AI Logic)**:
  - ⚡ **স্মার্ট রিপ্লাই (Smart Replies)**: বাংলা ও ইংরেজি কথোপকথন বুঝে প্রাসঙ্গিক কুইক রিপ্লাই।
  - 🛡️ **স্প্যাম ও ফিশিং প্রোটেকশন (Spam Shield)**: প্রতারণামূলক এসএমএস ও সন্দেহভাজন লিঙ্ক যাচাই।
  - 📝 **থ্রেড সারাংশ (Thread Summary)**: লম্বা এসএমএস চ্যাট এক নিমিষে সামারি করা।
  - ✨ **ম্যাজিক ড্রাফট পালিশ (Tone Polishing)**: ফরমাল, বন্ধুত্বপূর্ণ, বিনয়ী বা জরুরি ভাষায় মেসেজ রিরাইট।

- **রিয়েল-টাইম ওয়েব সিঙ্ক সিমুলেটর (Web Sync Bridge)**:
  - কম্পিউটার ব্রাউজার ও অন্যান্য ডিভাইসের জন্য ওয়েব সকেট ইভেন্ট ব্রিজিং।
  - লাইভ ইভেন্ট হিস্ট্রি ও কানেকশন টগল।

- **ক্লাউড সিঙ্ক ও ব্যাকআপ (Firebase Firestore)**:
  - Google Sign-In (Jetpack Credential Manager) দিয়ে সুরক্ষিত ক্লাউড ব্যাকআপ।

---

## 🚀 গিটহাব (GitHub) থেকে রিলিজ ও APK তৈরি করার নিয়ম

এই রিপোজিটরিতে **GitHub Actions CI/CD** স্বয়ংক্রিয়ভাবে কনফিগার করা আছে। আপনি ৩টি উপায়ে সরাসরি GitHub থেকে APK তৈরি ও রিলিজ করতে পারবেন:

### পদ্ধতি ১: গিট ট্যাগ পুশ করে অটোমেটিক GitHub Release তৈরি (সবচেয়ে সহজ)

আপনার লোকাল টার্মিনালে নিচের কমান্ডগুলো রান করুন:

```bash
# ১. গিটহাব রিমোট অ্যাড করুন (যদি এখনও না করে থাকেন)
git remote add origin https://github.com/আপনার-ইউজারনেম/Pulse-SMS.git

# ২. মূল কোড পুশ করুন
git branch -M main
git push -u origin main

# ৩. একটি নতুন ভার্সন ট্যাগ তৈরি করে পুশ করুন (যেমন: v1.0.0)
git tag v1.0.0
git push origin v1.0.0
```

⚡ **কী ঘটবে?**
- GitHub Actions স্বয়ংক্রিয়ভাবে চালু হবে।
- অ্যান্ড্রয়েড অ্যাপটি কম্পাইল করে `PulseSMS-v1.0.0-release.apk` এবং `PulseSMS-v1.0.0-debug.apk` তৈরি করবে।
- GitHub-এর **Releases** ট্যাবে একটি নতুন রিলিজ তৈরি করে সেখানে সরাসরি ডাউনলোডযোগ্য `.apk` ফাইল আপলোড করে দেবে!

---

### পদ্ধতি ২: গিটহাব ওয়েব ইন্টারফেস থেকে ম্যানুয়ালি রান করা (Workflow Dispatch)

১. আপনার GitHub রিপোজিটরি ওপেন করুন।
২. উপরের মেনু থেকে **Actions** ট্যাবে ক্লিক করুন।
৩. বাম পাশের তালিকা থেকে **Build & Release Android APK** সিলেক্ট করুন।
৪. ডানপাশের **Run workflow** বাটনে ক্লিক করুন।
৫. রিলিজের নাম (যেমন: `Pulse SMS v1.0.0`) লিখে সবুজ **Run workflow** বাটনে চাপ দিন।
৬. কাজ শেষ হলে **Actions run** এর ভিতরে **Artifacts** সেকশনে বা **Releases** সেকশনে APK পেয়ে যাবেন।

---

### পদ্ধতি ৩: লোকাল মেশিনে Gradle দিয়ে সরাসরি APK তৈরি

যদি আপনি নিজের কম্পিউটারে সরাসরি APK তৈরি করতে চান:

```bash
# ডিবাগ এপিকে তৈরি করতে:
gradle :app:assembleDebug

# তৈরি হওয়া APK ফাইলটি পাবেন এই লোকেশনে:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 ইনস্টলেশন ও পারমিশন নির্দেশিকা

1. আপনার ফোনে APK ফাইলটি ডাউনলোড করে ওপেন করুন।
2. "Install Unknown Apps" অনুমতি চাইলে তা সক্রিয় করুন।
3. Pulse SMS ওপেন করে স্ক্রিনের উপরের ব্যানারে **Grant SMS Access** বাটনে ট্যাপ করে মেসেজ পড়া ও রিসিভ করার অনুমতি দিন।
4. প্রয়োজনমতো **Set as Default SMS** বাটনে ক্লিক করে Pulse SMS-কে আপনার ফোনের ডিফল্ট মেসেজিং অ্যাপ হিসেবে সেট করুন।

---

## 🛠️ টেক স্ট্যাক ও আর্কিটেকচার

- **Language**: Kotlin 100%
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Local Database**: Room Database (SQLite with KSP)
- **AI Core**: Google Firebase AI Logic (`gemini-2.5-flash`)
- **Cloud & Auth**: Firebase Firestore & Jetpack Credential Manager (Google Sign-In)
- **CI/CD**: GitHub Actions
- **Testing**: Robolectric & JUnit 4
