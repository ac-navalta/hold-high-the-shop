# Firebase Spark (₱0), Branded HTML Email Relay, Custom `/auth/action` Page & Gemini Safety Gate Setup

**Hold High The Shop** is architected to run **100% end-to-end on the free Firebase Spark Plan (₱0/month — no credit card required)** paired with a free Google Apps Script HTML Email Relay, a custom branded Firebase Hosting verification page (`/auth/action`), and the free Google AI Studio Gemini API tier.

> **Why no Blaze plan needed?**
> - Compressed WebP photos (`<= 960px`, `< 180 KB`) are stored in dedicated Cloud Firestore `media/{mediaId}` documents and cached locally on each phone in `filesDir/media/`.
> - Branded HTML **"Verify My UC Email"** button emails and moderator notices are sent via [`docs/EMAIL_RELAY_APPS_SCRIPT.gs`](./EMAIL_RELAY_APPS_SCRIPT.gs) (`MailApp.sendEmail`) directly to `@students.uc-bcf.edu.ph` Microsoft Outlook inboxes at ₱0/month!
> - Clicking the email button opens our custom UC-branded verification & password-reset web page hosted on free **Firebase Hosting** (`https://<project-id>.web.app/auth/action`).

---

## Step 1: Create Your Free Firebase Project (2 minutes)

1. Go to [https://console.firebase.google.com](https://console.firebase.google.com) and click **Create a project**.
2. Name it `hold-high-the-shop` (keep it on the **Spark (Free)** plan — no billing account needed).
3. Disable Google Analytics (optional, faster setup) and click **Create project**.

---

## Step 2: Enable Email/Password Auth

1. In the Firebase Console sidebar, go to **Build → Authentication → Get started**.
2. Open the **Sign-in method** tab → click **Email/Password** → enable the first toggle (**Email/Password**) → click **Save**.
3. *(Recommended)* Under **Templates → Password reset** (and **Email address verification**), set Sender name to `Hold High The Shop (UC)`. After deploying Firebase Hosting in Step 8, point **Customize action URL** to `https://hold-high-the-shop.web.app/auth/action` so password resets and native Firebase links also use our custom UC-branded page!

---

## Step 3: Enable Cloud Firestore & Publish Security Rules

1. Go to **Build → Firestore Database → Create database**.
2. Choose **Standard edition** → pick `asia-southeast1` (Singapore, lowest latency for Baguio City) → start in **Production mode**.
3. Open the **Rules** tab, copy the full contents of [`firestore.rules`](../firestore.rules) from this repository, paste it into the editor, and click **Publish**.
   - **Important**: Re-publish [`firestore.rules`](../firestore.rules) after pulling updates so Firestore enforces single-use 192-bit verification link tokens (`email_verifications/{token}` + atomic batch redemption onto `users/{uid}`), `@students.uc-bcf.edu.ph` validation, per-thread chat isolation, and Console-only admin authorization!

---

## Step 4: Enable Branded HTML Verify Button & Moderation Emails (₱0 Google Apps Script)

1. Open [https://script.google.com](https://script.google.com) and click **New project**.
2. Copy the entire contents of [`docs/EMAIL_RELAY_APPS_SCRIPT.gs`](./EMAIL_RELAY_APPS_SCRIPT.gs), paste it into `Code.gs`, and click **Save**.
3. Click **Deploy → New deployment**:
   - Select type: **Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
   - Click **Deploy**, authorize Mail permissions, and copy the generated **Web app URL** (`https://script.google.com/macros/s/.../exec`).
4. Open `local.properties` at the root of this repository and add:
   ```properties
   EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec
   ```
   *(Optional: if you set `SECRET_TOKEN` in `Code.gs`, also add `EMAIL_WEBHOOK_SECRET=your_secret`. By default, verification buttons point automatically to `https://<your-firebase-project-id>.web.app/auth/action`; you can override this with `VERIFY_WEB_URL=https://...` if needed.)*
5. Also add `VITE_EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec` to `admin-web/.env.local` so moderator actions (listing removals, official warnings, suspensions, and reinstatements) send layouted HTML emails too!

---

## Step 5: Connect the Android App (`google-services.json`)

1. In Firebase Console → **Project Settings** (gear icon) → **Your apps → Add app → Android**.
2. Enter Android package name:
   ```text
   com.nevergrowold.holdhightheshop
   ```
3. Download **`google-services.json`** and place it at:
   ```text
   app/google-services.json
   ```
   *(Already listed in `.gitignore` so credentials are never pushed to GitHub.)*
4. **Skip the "Add Firebase SDK" Gradle screen in the Firebase Console**—click **Next → Continue to console**. Both `com.google.gms.google-services` (`4.6.0`) and Firebase Auth + Firestore SDKs are already pre-configured using Firebase BOM `34.12.0` (do not bump to `35.2.0` under AGP 9.4.1).
5. Sync/rebuild the app in Android Studio or via `./gradlew assembleDebug`.

---

## Step 6: Enable the Live Gemini 3.5 Flash-Lite Safety Gate (Free Tier)

1. Visit [https://aistudio.google.com/apikey](https://aistudio.google.com/apikey) and click **Create API key** (free tier).
2. Add your key to `local.properties`:
   ```properties
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
3. Sync/rebuild the Android app. Every Create & Edit Listing submission inspects both text and up to 3 compressed WebP photos via **Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`).

---

## Step 7: Launch the Admin Operations Dashboard (`/admin-web/`)

1. In Firebase Console → **Project Settings → Your apps → Add app → Web (`</>`)**, register `hold-high-the-shop-admin`, and copy the `firebaseConfig` object.
2. Copy `admin-web/.env.example` to `admin-web/.env.local` and paste your keys:
   ```env
   VITE_FIREBASE_API_KEY=...
   VITE_FIREBASE_AUTH_DOMAIN=...
   VITE_FIREBASE_PROJECT_ID=...
   VITE_FIREBASE_STORAGE_BUCKET=...
   VITE_FIREBASE_MESSAGING_SENDER_ID=...
   VITE_FIREBASE_APP_ID=...
   VITE_EMAIL_WEBHOOK_URL=https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec
   ```
3. **Create your Admin account** (Console-only for security):
   - In **Authentication → Users**, create (or pick) your moderator account and copy its **User UID**.
   - In **Firestore Database → Data**, create collection `admins` → Document ID: `<Admin User UID>` → add `role = "moderator"` and `email = "your-admin@students.uc-bcf.edu.ph"`.
4. Run the portal locally:
   ```bash
   cd admin-web
   npm install
   npm run dev
   ```

---

## Step 8: Deploy Custom Branded Verification & Password Reset Page (`/auth/action`)

`admin-web` includes our custom UC-branded verification & password-reset web handler ([`AuthActionPage.jsx`](../admin-web/src/AuthActionPage.jsx)) at `/auth/action`. It replaces Firebase's plain verification and password-reset page, requires an explicit button click before redeeming one-time tokens (preventing Microsoft 365 Safe Links scanners from consuming links early), unlocks the Android app automatically via real-time Firestore sync, and offers a one-tap **Open Hold High The Shop** deep link (`holdhightheshop://market`).

1. Build and deploy to free **Firebase Hosting (Spark Plan)** from your machine (ensure `admin-web/.env.local` is filled out first):
   ```bash
   cd admin-web && npm run build
   cd .. && npx firebase-tools deploy --only hosting
   ```
2. Verify it live in your browser by opening:
   ```text
   https://hold-high-the-shop.web.app/auth/action
   ```
3. In **Firebase Console → Authentication → Templates → Email address verification** → click the pencil (**Edit**) icon → **Customize action URL** → set:
   ```text
   https://hold-high-the-shop.web.app/auth/action
   ```
   Click **Save**. Firebase automatically applies this custom Action URL across **Email address verification**, **Password reset**, and **Email address change** templates!
