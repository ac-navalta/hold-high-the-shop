# Firebase Spark (₱0) & Gemini Safety Gate Setup Guide

**Hold High The Shop** is architected to run **100% end-to-end on the free Firebase Spark Plan (₱0/month — no credit card required)** plus the free Google AI Studio Gemini API tier.

> **Why no Blaze plan needed?**
> Compressed WebP photos (`<= 960px`, `< 180 KB`) are stored in dedicated Cloud Firestore `media/{mediaId}` documents and cached locally on each phone in `filesDir/media/`. You never need paid Cloud Storage or Cloud Functions!

---

## Step 1: Create Your Free Firebase Project (2 minutes)

1. Go to [https://console.firebase.google.com](https://console.firebase.google.com) and click **Create a project**.
2. Name it `hold-high-the-shop` (keep it on the **Spark (Free)** plan — no billing account needed).
3. Disable Google Analytics (optional, faster setup) and click **Create project**.

---

## Step 2: Enable Email/Password Auth (Real UC Outlook Verification)

1. In the Firebase Console sidebar, go to **Build → Authentication → Get started**.
2. Open the **Sign-in method** tab → click **Email/Password** → enable the first toggle (**Email/Password**) → click **Save**.
3. *(Optional polish)* Open **Templates → Email address verification** and customize the sender name to `Hold High The Shop (UC)`.
4. Every student who registers with `@students.uc-bcf.edu.ph` will receive a real verification email directly in their UC Microsoft Outlook inbox!

---

## Step 3: Enable Cloud Firestore & Deploy Security Rules

1. Go to **Build → Firestore Database → Create database**.
2. Choose **Standard edition** → pick `asia-southeast1` (Singapore, lowest latency for Baguio City) → start in **Production mode**.
3. Open the **Rules** tab, copy the full contents of [`firestore.rules`](../firestore.rules) from this repository, paste it into the editor, and click **Publish**.
   - These rules enforce strictly `@students.uc-bcf.edu.ph`, `email_verified == true`, non-suspended accounts, per-thread chat participant isolation, owner-only listing edits, and Console-only admin authorization!

---

## Step 4: Connect the Android App (`google-services.json`)

1. In Firebase Console → **Project Settings** (gear icon) → **Your apps → Add app → Android**.
2. Enter Android package name:
   ```text
   com.nevergrowold.holdhightheshop
   ```
3. Download **`google-services.json`** and place it at:
   ```text
   app/google-services.json
   ```
   *(Already listed in `.gitignore` so your team's credentials are never accidentally pushed to GitHub!)*
4. **Skip the "Add Firebase SDK" Gradle screen in the Firebase Console**—click **Next → Continue to console**. Both the `com.google.gms.google-services` plugin (`4.6.0`) and Firebase Auth + Firestore SDKs are **already pre-configured** in `build.gradle.kts` and `app/build.gradle.kts` (using Firebase BOM `34.12.0` for Kotlin compiler compatibility—do not replace it with `35.2.0`).
5. Sync/rebuild the app in Android Studio (`Sync Project with Gradle Files`) or via `./gradlew assembleDebug`—Gradle automatically detects `app/google-services.json` and activates live Firebase Auth + Cloud Firestore sync!

---

## Step 5: Enable the Live Gemini 3.5 Flash-Lite Safety Gate (Free Tier)

1. Visit [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey) and click **Create API key** (free tier).
2. Open your local `local.properties` file at the root of the repo (already in `.gitignore`) and add:
   ```properties
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
3. Sync/rebuild the Android app. Now every Create & Edit Listing submission inspects BOTH text AND up to 3 compressed WebP photos via live **Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`)!
   - *Offline / key missing fallback*: `LocalSafetyFilter` still blocks prohibited keywords immediately, and safe listings fall back to `aiCheckStatus = "PENDING_REVIEW"` so moderators can verify them in the Admin Web Portal.

---

## Step 6: Launch the Admin Web Portal (`/admin-web/`)

1. In Firebase Console → **Project Settings → Your apps → Add app → Web (`</>`)**, register `hold-high-the-shop-admin`, and copy the `firebaseConfig` object.
2. In this repo, copy `admin-web/.env.example` to `admin-web/.env.local` and paste the 6 values:
   ```env
   VITE_FIREBASE_API_KEY=...
   VITE_FIREBASE_AUTH_DOMAIN=...
   VITE_FIREBASE_PROJECT_ID=...
   VITE_FIREBASE_STORAGE_BUCKET=...
   VITE_FIREBASE_MESSAGING_SENDER_ID=...
   VITE_FIREBASE_APP_ID=...
   ```
3. **Create your Admin account** (Console-only for security):
   - In **Authentication → Users**, create (or pick) your moderator account and copy its **User UID**.
   - In **Firestore Database → Data**, click **Start collection** → Collection ID: `admins` → Document ID: `<paste Admin User UID>` → add field `role` (string) = `"moderator"` and `email` (string) = `"your-admin@students.uc-bcf.edu.ph"`.
4. Run the portal locally:
   ```bash
   cd admin-web
   npm install
   npm run dev
   ```
5. Open the printed local URL, sign in with your Admin credentials, and moderate reports, AI safety logs, users, and live campus announcements!
