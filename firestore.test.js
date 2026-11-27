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

test("Pairing session: paired device and web can write and list real-time session messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const sessionRef = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123");

  await assertSucceeds(
    sessionRef.set({
      sessionId: "pulse_pair_test123",
      status: "CONNECTED",
      deviceName: "Pixel 9",
      userId: "mobile_device_1234",
      createdAt: new Date(),
    })
  );

  const msgRef = sessionRef.collection("messages").doc("sms_1001_ab12");
  await assertSucceeds(
    msgRef.set({
      id: "sms_1001_ab12",
      threadAddress: "bKash",
      senderAddress: "bKash",
      recipientAddress: "Me",
      body: "Your OTP is 492817",
      isIncoming: true,
      category: "OTP",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(sessionRef.collection("messages").get());

  // Invalid category or mismatched id should fail
  await assertFails(
    sessionRef.collection("messages").doc("bad_msg").set({
      id: "wrong_id",
      threadAddress: "bKash",
      senderAddress: "bKash",
      recipientAddress: "Me",
      body: "Test",
      isIncoming: true,
      category: "OTP",
      createdAt: new Date(),
    })
  );
});

test("Pairing session: web can enqueue outgoing SMS request and phone can mark SENT", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const reqRef = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("outgoing_requests").doc("req_101");

  await assertSucceeds(
    reqRef.set({
      id: "req_101",
      recipient: "+8801711223344",
      body: "Hello from Web!",
      status: "PENDING",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(reqRef.update({ status: "SENT" }));
});

test("Pairing session: mobile and web can write and list real-time mirrored notifications", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const notifCol = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("notifications");

  await assertSucceeds(
    notifCol.doc("mobnotif_1001_ab12").set({
      id: "mobnotif_1001_ab12",
      title: "WhatsApp • Rahim",
      body: "ভাই, মিটিং কখন?",
      appName: "WhatsApp",
      packageName: "com.whatsapp",
      sourceDevice: "MOBILE",
      category: "APP",
      isTest: false,
      createdAt: new Date(),
    })
  );

  await assertSucceeds(
    notifCol.doc("webnotif_1002_cd34").set({
      id: "webnotif_1002_cd34",
      title: "Web Test Ping",
      body: "Simultaneous browser -> mobile notification",
      appName: "Pulse Web",
      packageName: "web.pulse.companion",
      sourceDevice: "WEB",
      category: "TEST",
      isTest: true,
      createdAt: new Date(),
    })
  );

  await assertSucceeds(notifCol.get());

  // Invalid sourceDevice must fail
  await assertFails(
    notifCol.doc("bad_notif_1").set({
      id: "bad_notif_1",
      title: "Bad Source",
      body: "Should reject",
      appName: "Unknown",
      packageName: "com.unknown",
      sourceDevice: "SERVER",
      category: "TEST",
      isTest: true,
      createdAt: new Date(),
    })
  );
});

test("Pairing session: web can issue camera commands and mobile can stream live camera frames", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const camDocRef = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("camera").doc("live");

  await assertSucceeds(
    camDocRef.set({
      id: "live",
      command: "START_BACK",
      commandSeq: 1,
      status: "STARTING",
      updatedAt: new Date(),
    }, { merge: true })
  );

  await assertSucceeds(
    camDocRef.set({
      status: "ACTIVE",
      lens: "BACK",
      torchOn: false,
      frameBase64: "data:image/jpeg;base64,/9j/4AAQSkZJRg==",
      frameSeq: 1,
      width: 480,
      height: 640,
      deviceName: "Pixel 9",
      updatedAt: new Date(),
    }, { merge: true })
  );

  await assertSucceeds(camDocRef.get());

  // Invalid command should be rejected
  await assertFails(
    camDocRef.set({
      command: "HACK_DEVICE",
      updatedAt: new Date(),
    }, { merge: true })
  );
});

test("Pairing session: mobile can sync call logs and web can list/delete call history", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const callCol = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("call_logs");

  await assertSucceeds(
    callCol.doc("call_1700000_ab12").set({
      id: "call_1700000_ab12",
      number: "+8801711223344",
      contactName: "Karim Vai",
      callType: "INCOMING",
      durationSec: 135,
      simLabel: "SIM 1",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(callCol.get());

  // Invalid callType should fail
  await assertFails(
    callCol.doc("call_bad_1").set({
      id: "call_bad_1",
      number: "+8801711223344",
      callType: "INVALID_TYPE",
      durationSec: 10,
      createdAt: new Date(),
    })
  );
});

test("Pairing session: web can issue microphone commands and mobile can stream live audio chunks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const micDocRef = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("microphone").doc("live");

  await assertSucceeds(
    micDocRef.set({
      id: "live",
      command: "START",
      commandSeq: 1,
      status: "STARTING",
      updatedAt: new Date(),
    }, { merge: true })
  );

  await assertSucceeds(
    micDocRef.set({
      status: "ACTIVE",
      audioBase64: "AAAAAQACAA==",
      chunkSeq: 1,
      sampleRate: 16000,
      audioLevel: 42,
      deviceName: "Pixel 9",
      updatedAt: new Date(),
    }, { merge: true })
  );

  await assertSucceeds(micDocRef.get());

  // Invalid command should fail
  await assertFails(
    micDocRef.set({
      command: "WIRE_TAP",
      updatedAt: new Date(),
    }, { merge: true })
  );
});

test("Pairing session: mobile can upload call recording mapped with number & GPS location, and web can list/delete", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const recCol = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("call_recordings");

  await assertSucceeds(
    recCol.doc("rec_1700000_ab12").set({
      id: "rec_1700000_ab12",
      phoneNumber: "+8801711223344",
      contactName: "Rahim Telecom",
      callType: "INCOMING",
      durationSec: 42,
      audioBase64: "data:audio/mp4;base64,AAAAIGZ0eXBpc29t",
      mimeType: "audio/mp4",
      latitude: 23.8103,
      longitude: 90.4125,
      locationLabel: "Gulshan, Dhaka",
      deviceName: "Pixel 9",
      createdAt: new Date(),
    })
  );

  await assertSucceeds(recCol.get());

  // Invalid coordinates (> 90 latitude) should fail
  await assertFails(
    recCol.doc("rec_bad_loc").set({
      id: "rec_bad_loc",
      phoneNumber: "+8801711223344",
      callType: "OUTGOING",
      durationSec: 12,
      audioBase64: "data:audio/mp4;base64,AAAAIGZ0eXBpc29t",
      mimeType: "audio/mp4",
      latitude: 120.5,
      longitude: 90.4125,
      createdAt: new Date(),
    })
  );
});

test("Pairing session: web can issue call_recorder commands with targetNumber", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const recStateRef = unauthDb.collection("pairing_sessions").doc("pulse_pair_test123").collection("call_recorder").doc("live");

  await assertSucceeds(
    recStateRef.set({
      id: "live",
      command: "START",
      commandSeq: 1,
      targetNumber: "+8801811002233",
      status: "STARTING",
      updatedAt: new Date(),
    }, { merge: true })
  );

  await assertSucceeds(recStateRef.get());
});
