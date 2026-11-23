# Team of 5 Live Demo Catalog & End-to-End Walkthrough

Instead of injecting fake pre-seeded accounts or items from Settings, your team of 5 signs up with **real `@students.uc-bcf.edu.ph` UC Outlook accounts** and populates authentic campus items so every listing, chat, handover code, rating, and admin action belongs to a real student!

---

## Suggested 5-Teammate Roles & Real Campus Catalog

Each teammate registers on their own phone with their real `@students.uc-bcf.edu.ph` email and verifies the link in UC Outlook.

### Student 1 — CITCS (3rd Year)
1. **Casio fx-991EX Scientific Calculator**
   - **Type:** Sell • **Price:** `₱1,150` • **Condition:** Good
   - **College Tags:** `CITCS`, `COE`, `COA`
   - **Meetup:** `UC Main Gate Security Desk` • **Note:** `Near the ID tap gate`
   - **Description:** Used for 2 terms, complete with original hard slide case and crisp LCD.
2. **USB-C 7-in-1 Laptop Hub (HDMI + Ethernet)**
   - **Type:** Lend • **Price:** `₱40 / day` • **Longest loan:** `7 days (1 week)` • **Condition:** Like new
   - **College Tags:** *(leave unselected → **All colleges**)*
   - **Meetup:** `CITCS Building Entrance` • **Note:** `3rd floor lobby near M304`
   - **Description:** Great for thesis defense presentations and lab demos.

### Student 2 — CONAHS (2nd Year)
3. **UC White Laboratory Gown (Medium)**
   - **Type:** Lend • **Price:** `₱35 / day` • **Longest loan:** `14 days (2 weeks)` • **Condition:** Like new
   - **College Tags:** `CONAHS`, `CAS`
   - **Meetup:** `UC Library Lobby` • **Note:** `Beside the reading lounge bench`
   - **Description:** Freshly laundered, crisp UC patch, zero stains.
4. **Anatomy & Physiology Flashcard Deck (Printed)**
   - **Type:** Sell • **Price:** `₱0` (**FREE giveaway!**) • **Condition:** Good
   - **College Tags:** `CONAHS`
   - **Meetup:** `UC Library Lobby`
   - **Description:** Passing these on for free to incoming freshmen after passing finals!

### Student 3 — COE (4th Year)
5. **Engineering Drawing T-Square & Compass Set**
   - **Type:** Swap • **Wants:** `Arduino Uno R3 kit or Data Structures reviewer` • **Condition:** Good
   - **College Tags:** `COE`, `CAFA`
   - **Meetup:** `UC Main Gate Security Desk`
   - **Description:** Complete 24-inch acrylic T-square, 30/60 & 45 triangles, and metal compass in hard tube.
6. **Breadboard & Jumper Wire Electronics Kit**
   - **Type:** Lend • **Price:** `₱25 / day` • **Longest loan:** `5 days` • **Condition:** Good
   - **College Tags:** `COE`, `CITCS`
   - **Meetup:** `CITCS Building Entrance`
   - **Description:** 830-tie breadboard, resistor pack, LEDs, and male-to-male jumper wires.

### Student 4 — CBA / COA (3rd Year)
7. **Financial Accounting Vol. 1 Textbook (Valix)**
   - **Type:** Sell • **Price:** `₱480` • **Condition:** Fair
   - **College Tags:** `COA`, `CBA`
   - **Meetup:** `UC Canteen Area` • **Note:** `Near the south stairs entrance`
   - **Description:** Light pencil annotations in chapters 1–4, all pages intact and clean.
8. **UC PE Uniform Set (Large — Top + Jogging Pants)**
   - **Type:** Sell • **Price:** `₱350` • **Condition:** Like new
   - **College Tags:** *(leave unselected → **All colleges**)*
   - **Meetup:** `UC Main Gate Security Desk`
   - **Description:** Only worn for one trimester of PATHFIT, washed and ready to wear.

### Student 5 — CAFA / CTE (2nd Year)
9. **Rechargeable Clip-On Desk Lamp (Warm/Cool LED)**
   - **Type:** Sell • **Price:** `₱290` • **Condition:** Like new
   - **College Tags:** *(leave unselected → **All colleges**)*
   - **Meetup:** `UC Library Lobby`
   - **Description:** 3 brightness modes, holds 6 hours of charge for late-night Baguio study sessions.
10. **Arduino Starter Kit with LCD & Ultrasonic Sensor**
    - **Type:** Swap • **Wants:** `Drafting T-Square or Scientific Calculator` • **Condition:** Like new
    - **College Tags:** `CITCS`, `COE`
    - **Meetup:** `CITCS Building Entrance`
    - **Description:** Working Uno clone, USB cable, 16x2 LCD, HC-SR04 sensor, and servo motor.

---

## Live 5-Phone End-to-End Demo Script (For Presentation / Defense)

1. **Strict UC Domain Gate & Verification**
   - Try registering with `student@gmail.com` → blocked instantly by `UcEmailValidator`.
   - Register with `@students.uc-bcf.edu.ph` → open UC Outlook → tap real verification link → return to app → automatically unlocks Campus Market!
2. **Hybrid Multimodal Prohibited-Item Safety Gate (Headliner API)**
   - Open **Post an item**, attach a photo, try title `"Disposable Vape 5000 Puffs"` or `"Leaked Midterm Exam Answers"` → blocked with **Prohibited Item Notice** dialog naming `UC Marketplace Guidelines §3.x`, AND instantly logged in the Admin Web Portal's **AI Safety Log**!
   - Try a disguised photo (e.g., photo of a vape/alcohol labeled `"Pencil Case"`) with Gemini API key active → Gemini 3.5 Flash-Lite inspects the WebP photo and blocks it too!
3. **1–10 Photos, Multi-Select College Tags & Day-Unit Lending**
   - Post a real item with 3+ photos, tag `CITCS` + `COE` (or leave untagged for `All colleges`), verify the restored dual-ring celebration stamp plays on `ListingPostedActivity`!
   - Switch to **Market** tab on Phone 2: filter `CITCS` (tagged items rank first, `All colleges` items appear cleanly below!), filter `All colleges`, search by keyword.
4. **2-Step SecureRandom Handover + Context-Aware Chat Proposals**
   - Phone 2 taps **Message** directly before reserving, then taps **Borrow** for `3 days` (or custom days!). Notice the buyer's handover code stays **locked** while pending.
   - Phone 1 receives notification → taps **Review request → Accept** → Phone 2 immediately receives a **cryptographically random 4-digit handover code**!
   - In **Chat**, tap the **`+`** icon → tap **Propose meetup change** → pick a new campus spot/time → send! Phone 2 sees the interactive Proposal Card and taps **Confirm change** → schedule updates live AND `AlarmManager` reminders reschedule automatically!
   - At meetup, Phone 1 taps **Enter code**, types Phone 2's 4-digit code → loan begins!
   - Tap **`+`** in Chat again → now **Propose return extension** (`+2 days`) appears dynamically! Confirm extension → due date & alarms extend cleanly!
   - Phone 2 taps **I've returned it** → Phone 1 taps **Confirm returned** → sees *"Keep listed or Pause listing?"* prompt → both leave blind 5-star ratings!
5. **Notifications, Services & Broadcasts Live Proof**
   - Watch the live **Upcoming Meetup / Active Loan Countdown** tick every second on Home (`MeetupCountdownService` bound service).
   - Toggle Airplane Mode on/off → see the offline banner (`NetworkStateMonitor`) and reconnect sync (`MarketplaceSyncService`).
   - Reply straight from an Android chat notification via **Inline Direct Reply** (`DealAlertReceiver` + `RemoteInput`).
6. **3-Report Auto Escalation & Admin Web Portal**
   - Have 3 different students report a test listing → watch it automatically flip to `UNDER_REVIEW` and disappear from Campus Market!
   - Open the **Admin Web Portal** on a laptop → view the real photo decoded from Firestore `media/`, click **Approve & Restore** or **Remove Listing** (auto-notifies seller + reporter), publish a **Campus Announcement** (appears live on all 5 phones!), and inspect the immutable **Audit Trail**!
