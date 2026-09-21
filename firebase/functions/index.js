const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "europe-west1", maxInstances: 20 });

const db = admin.firestore();

async function overRateLimit(uid) {
  const ref = db.collection("rateLimits").doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    let windowStart = snap.exists ? snap.get("windowStart") : now;
    let count = snap.exists ? snap.get("count") : 0;
    if (now - windowStart > 60_000) {
      windowStart = now;
      count = 0;
    }
    count += 1;
    tx.set(ref, { windowStart, count });
    return count > 40;
  });
}

async function tokensFor(uid) {
  const snap = await db.collection("users").doc(uid).collection("tokens").get();
  return snap.docs.map((doc) => doc.id).filter(Boolean);
}

async function sendData(uid, tokens, data) {
  if (!tokens.length) return;
  const response = await admin.messaging().sendEachForMulticast({
    tokens,
    data: Object.fromEntries(Object.entries(data).map(([key, value]) => [key, String(value ?? "")])),
    android: { priority: "high", ttl: 45_000 },
  });
  await Promise.all(response.responses.map(async (item, index) => {
    const code = item.error && item.error.code;
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
      await db.collection("users").doc(uid).collection("tokens").doc(tokens[index]).delete();
    }
  }));
}

exports.onMessageCreated = onDocumentCreated("chats/{chatId}/messages/{messageId}", async (event) => {
  const message = event.data && event.data.data();
  if (!message || message.deleted) return;
  const chatId = event.params.chatId;
  const senderId = message.senderId;
  if (!senderId) return;
  if (typeof message.text === "string" && message.text.length > 4000) {
    await event.data.ref.delete();
    return;
  }
  if (await overRateLimit(senderId)) {
    await event.data.ref.delete();
    return;
  }
  const chatSnap = await db.collection("chats").doc(chatId).get();
  const chat = chatSnap.data();
  if (!chat) return;
  const senderSnap = await db.collection("users").doc(senderId).get();
  const senderName = (senderSnap.data() && senderSnap.data().displayName) || "BlazeMessenger";
  const preview = (message.text || message.fileName || message.type || "Message").toString().slice(0, 140);
  const mentions = Array.isArray(message.mentions) ? message.mentions : [];
  const members = Array.isArray(chat.memberIds) ? chat.memberIds : [];

  await Promise.all(members.filter((memberId) => memberId !== senderId).map(async (memberId) => {
    const memberSnap = await db.collection("chats").doc(chatId).collection("members").doc(memberId).get();
    const mutedUntil = (memberSnap.data() && memberSnap.data().mutedUntil) || 0;
    if (mutedUntil > Date.now()) return;
    await db.collection("chats").doc(chatId).update({
      [`unreadCounts.${memberId}`]: admin.firestore.FieldValue.increment(1),
    });
    const memberUser = await db.collection("users").doc(memberId).get();
    const username = memberUser.data() && memberUser.data().usernameLower;
    const mention = mentions.includes("all") || (username && mentions.includes(username));
    const title = chat.type === "group" ? `${senderName}` : senderName;
    await sendData(memberId, await tokensFor(memberId), {
      type: "message",
      chatId,
      messageId: event.params.messageId,
      title,
      body: preview,
      mention: mention ? "1" : "0",
      senderId,
    });
  }));
});

exports.onCallCreated = onDocumentCreated({
  document: "calls/{callId}",
  timeoutSeconds: 70,
}, async (event) => {
  const call = event.data && event.data.data();
  if (!call) return;
  const calleeTokens = await tokensFor(call.calleeId);
  await sendData(call.calleeId, calleeTokens, {
    type: "incoming_call",
    callId: event.params.callId,
    callerId: call.callerId,
    callerName: call.callerName || "",
    callerPhoto: call.callerPhoto || "",
    callType: call.type || "voice",
  });
  await new Promise((resolve) => setTimeout(resolve, 45_000));
  const fresh = await event.data.ref.get();
  if (fresh.exists && fresh.get("state") === "ringing") {
    await fresh.ref.update({ state: "missed", endedAt: Date.now() });
    await sendData(call.calleeId, calleeTokens, {
      type: "missed_call",
      callId: event.params.callId,
      callerName: call.callerName || "",
    });
  }
});

exports.deleteAccount = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }
  const uid = request.auth.uid;
  const userRef = db.collection("users").doc(uid);
  const user = await userRef.get();
  const usernameLower = user.exists ? user.get("usernameLower") : "";
  if (usernameLower) {
    const nameRef = db.collection("usernames").doc(usernameLower);
    const name = await nameRef.get();
    if (name.exists && name.get("uid") === uid) await nameRef.delete();
  }
  const subcollections = ["tokens", "blocked", "readCursors"];
  for (const name of subcollections) {
    const snap = await userRef.collection(name).get();
    await Promise.all(snap.docs.map((doc) => doc.ref.delete()));
  }
  const stories = await db.collection("stories").where("authorId", "==", uid).get();
  await Promise.all(stories.docs.map((doc) => doc.ref.delete()));
  await userRef.delete();
  try {
    await admin.storage().bucket().deleteFiles({ prefix: `profiles/${uid}/` });
  } catch (error) {
    console.error("profile-storage-cleanup", error);
  }
  await admin.auth().deleteUser(uid);
  return { deleted: true };
});
