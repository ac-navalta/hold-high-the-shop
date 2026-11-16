# Build prompt: Hold High The Shop (front-end prototype)

You are building the **front-end prototype** of *Hold High The Shop*, an Android
campus marketplace where verified University of the Cordilleras (UC) students
sell, rent out and swap items with each other. This phase is **UI/UX only**:
every screen, flow and state must look and behave correctly, but there is **no
backend**. All data is mock data in the app and resets when the app closes.

## Tech and constraints

- Kotlin, Android Views (XML layouts), `AppCompat` + Material Components. No Compose.
- One Activity per screen, all extending a shared `BaseActivity`. Edge-to-edge,
  with system bar and keyboard insets handled in one helper (`BarInsets`).
- Portrait only. Phone layouts only.
- Do **not** add networking, databases, accounts, real payments, real photo
  picking, real sharing to other apps, maps SDKs or notifications. Where those
  would exist, show the UI and a believable placeholder result instead.
- Mock data lives in one place (`Listings.kt`: listings, sellers, exchanges,
  meetup points, colleges) and session state in one in-memory object
  (`UserState`: saved, reserved, loans, finished deals). When the backend
  arrives, only these should need replacing.

## Design language (follow the Figma file "CC17", page "With Flow")

- Colours: UC green `#07683B` (primary, headers, buttons), cream `#F9F6E7`
  (backgrounds), mint `#C1D9CE` / `#E4EFEA` (tiles, soft fills), text
  `#1A1A1A` / `#6B6B6B`, divider `#E6E2D2`. Accents: amber for swaps,
  red `#C62828` only for destructive actions and overdue states.
- Type: Poppins (regular, medium, semibold, bold) everywhere.
- Shapes: white cards with 16–20dp corners and soft elevation; pill chips and
  buttons; 52dp primary buttons.
- Grid: content 20dp from each screen edge, 12dp between side-by-side items,
  24dp between sections. Cards must not use compat padding, so their visible
  edge sits on the grid. Spacing values live in `res/values/dimens.xml`.
- Dropdowns use the Figma style: white card, one option per row, green bar on
  the selected row. They open on whichever side of the field has room.
- Bottom sheets: cream, rounded top corners, a small grey handle.
- Bottom tab bar: Home, Market, Chats, Profile. It hides while the keyboard
  is open; screens with an action bar keep it above the keyboard.
- Popups and sheets opened while typing must wait for the keyboard to close
  before appearing.

## Screens and what they must do

1. **Landing → Login.** Splash, then a sign-in card for UC email. Sign in
   goes straight to Home (no email check in this phase). The card and button
   stay visible above the keyboard.
2. **Home.** Greeting; stat cards ("items exchanged" → Profile Completed,
   "active listings" → Profile Active); a return reminder card for the most
   urgent borrowed item due within 3 days (red if overdue); "Post an item";
   "Browse programs" as a sideways row of all 11 colleges (CITCS, COA, COE,
   CAFA, CTE, CONAHS, CBA, CHTM, CAS, CCJE, COL), each opening the Market
   filtered to it; "Fresh on campus" with the newest listings.
3. **Market.** Search (live), college chips, a Filters sheet (condition,
   listing type, price range, with a live "Show N items" button), and a sort
   dropdown (Newest, price low/high). Result count and an empty state.
   Every card shows a type tag: FOR SALE (mint), FOR RENT (outline),
   FOR SWAP (amber). Never show the user's own listings or anything they have
   reserved, borrowed, bought or offered a swap for. Header bookmark → Saved.
4. **Listing detail.** Photo area with back, share and save buttons. Condition,
   title, price ("₱380 / week" for rent and "Swap" for swap, both smaller than
   a normal price), description, a "Looking to swap for" card on swaps, seller
   card (→ seller profile), reliability stats, and a meetup card (→ sheet with
   safety notes and Directions). The bottom bar changes with state:
   - someone else's listing: Report (icon) + Reserve / Rent / Offer a swap;
   - reserved by you: Message + red Cancel (or Withdraw for swaps), and a
     status card with "I got the item" / "I picked it up" / "Swap done";
   - your own listing: Edit listing + Mark as sold / Lend it out / Mark as swapped;
   - on loan: status card with due date and countdown, Message + the return action.
5. **Reserve sheet.** Meetup point dropdown; day chips that list only days the
   seller is free plus "Pick a date…" (calendar with other days greyed out);
   time chips for that day's free slots plus "Other time…". Rent listings add
   "Rent for" chips up to the lender's limit and show "Return by … • ₱X total".
6. **Swap offer sheet.** The item, what the seller wants, the user's own
   listings to pick from, then "Send swap offer" → chat with a "Swap offer
   sent" card.
7. **Create / Edit listing.** Photo tiles (toggle a placeholder "added" state),
   title, college dropdown, condition, Sell / Rent / Swap. Sell: Price. Rent:
   "Price per week" and "Longest rental" (1 week, 2 weeks, 1 month, 2 months,
   Semester). Swap: "Looking for" instead of price. Description with a
   300-character counter. Meetup point dropdown, prefilled from Settings.
   "When are you free to meet?" as one or more available-time cards, each with
   Mon–Sun toggles and From / To (any time of day, end after start), plus
   "Add available time". Post checks required fields and scrolls to the first
   missing one. Edit mode is the same form, prefilled, with Cancel / Save changes.
8. **Loans (lending and borrowing).** Lender starts a loan with "Lend it out"
   (pick who and how long). Borrower taps "I've returned it", which only
   reports the return; the lender sees "says it's returned" and chooses
   Confirm returned (→ Completed) or Not received (red, loan stays open). The
   lender can also Mark as returned directly. Overdue loans show in red.
9. **Profile.** Avatar, reliability card, tabs:
   - Active: chips "All · Borrowing · Lent out · Reserved · Your listings",
     each with a count, hiding empty groups. All shows each group under a
     heading with its due dates and role tags (BORROWING, LENT OUT, BUYING,
     SWAPPING, SELLING, LENDING).
   - Completed: every finished exchange with a SOLD / BOUGHT / LENT /
     BORROWED / SWAPPED tag, each opening Transaction details.
   - Saved.
   "…" menu: Edit profile, Settings, Log out (with confirmation).
10. **Transaction details.** Item, price, tag, date, the other student (→
    profile), reference, meetup point, payment, both ratings, Message button.
11. **Chats.** Search, filter chips (All, Buying & borrowing, Selling &
    lending, Swaps), and a role tag on every conversation. **Chat thread:** the
    header shows the person, the item strip says "YOU'RE BORROWING" etc., the
    send button adds a bubble, and "+" opens a sheet (photo placeholder,
    meetup point, meetup time, view listing).
12. **Seller profile, Saved, Notifications, Settings, Edit profile, Report
    listing (+ received), Listing posted, Share sheet, Directions.**
    - Share shows a preview, the link and Copy link, with a note that sharing
      to other apps comes later.
    - Directions is a pinch-zoom placeholder campus map with a walking route
      from the Main Gate, a walking time and an "Illustrative, not to scale"
      label.
    - Settings groups Account, Notifications, Marketplace (default meetup
      point), Privacy and safety, Support, and Log out.

## Out of scope for now

Real authentication and any email check, a server or
database, persistence across app restarts, payments, real photo upload,
push notifications, real maps or location, sharing outside the app, the other
student's side of any flow (they never reply or confirm), ratings input,
landscape and tablet layouts, animations and transitions.

## Done when

- Every tappable element on every screen leads somewhere or gives visible
  feedback; nothing is a dead button.
- Every text field can be typed in, with the right keyboard (numbers only for
  price), and stays visible above the keyboard.
- All flows above can be walked through end to end with the mock data:
  reserve → got it → Completed; rent → picked up → returned → confirmed;
  swap offer → swap done; lend out → borrower reports → lender confirms.
- Every screen follows the colours, Poppins type and the 20dp / 12dp / 24dp grid.
- The app builds with no errors, and every screen opens without crashing.
