const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write user profile", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set({
    userId: ALICE_UID,
    displayName: "Alice",
    email: "alice@example.com",
    createdAt: new Date(),
    updatedAt: new Date()
  }));
});

test("Owner: Alice can create and update her user profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userRef = aliceDb.collection("users").doc(ALICE_UID);

  // Initial creation
  await assertSucceeds(
    userRef.set({
      userId: ALICE_UID,
      displayName: "Alice Tester",
      email: "alice@example.com",
      createdAt: new Date(),
      updatedAt: new Date()
    })
  );

  await assertSucceeds(userRef.get());

  // Subsequent update
  await assertSucceeds(
    userRef.update({
      displayName: "Alice Updated",
      updatedAt: new Date()
    })
  );

  // Set with merge (as done in Android app)
  await assertSucceeds(
    userRef.set({
      userId: ALICE_UID,
      displayName: "Alice Set Merge",
      email: "alice@example.com",
      updatedAt: new Date()
    }, { merge: true })
  );
});

test("Cross-user: Bob cannot write to Alice's profile", async () => {
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Hacker Bob",
      email: "bob@example.com",
      createdAt: new Date(),
      updatedAt: new Date()
    })
  );
});

test("Unauthenticated user: cannot read messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("messages").get());
});

test("Cross-user: Bob cannot read Alice's messages", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(ALICE_UID).collection("messages").doc("msg1").set({
      id: "msg1",
      threadAddress: "+8801700112233",
      senderAddress: "Me",
      recipientAddress: "+8801700112233",
      body: "Secret SMS",
      isIncoming: false,
      category: "PERSONAL",
      createdAt: new Date(),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg1").get());
});

test("Owner: Alice can create and read her own message", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg1");

  await assertSucceeds(
    docRef.set({
      id: "msg1",
      threadAddress: "+8801700112233",
      senderAddress: "Me",
      recipientAddress: "+8801700112233",
      body: "Hello from Alice",
      isIncoming: false,
      category: "PERSONAL",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(docRef.get());
});

test("Owner: Schema rejection on invalid category", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("msg_bad");

  await assertFails(
    docRef.set({
      id: "msg_bad",
      threadAddress: "+8801700112233",
      senderAddress: "Me",
      recipientAddress: "+8801700112233",
      body: "Bad category",
      isIncoming: false,
      category: "INVALID_CAT",
      createdAt: new Date(),
    })
  );
});
