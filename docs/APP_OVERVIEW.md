# Hold High The Shop: App Overview and Ship Plan

Context document for two lab activities:

- **Lab Act 5**: Intents, fully usable widgets, Toast messages, Manifest updates
- **Lab Act 6**: External API integration, with comments explaining the API, the integration process and the API key

It covers what the app is, what exists in the code today, what each screen should do in the finished version, and a concrete plan for both labs.

---

## 1. What the app is

**Hold High The Shop** is a campus marketplace for University of the Cordilleras (UC) students. Students sell, rent or swap secondhand school items such as uniforms, lab gowns, calculators, books and drawing sets. Every transaction is meant to happen face to face at a safe on-campus meetup point.

Tagline: *"Pass it on. Make campus greener."*

Core ideas:

| Idea | How it shows up in the app |
|---|---|
| UC students only | Login asks for a `@s.uc-bcf.edu.ph` email |
| Organised by college | Items are tagged CITCS, CONAHS, CBA or CEA |
| Three listing types | Sell, Rent, Swap |
| Safe exchange | Every listing names a meetup point (Main Gate Security Desk, Library Lobby, CITCS Entrance, Canteen) |
| Trust | Sellers carry a "UC Verified" badge and a Reliability score (rating, on-time %, completed count) |
| Moderation | Any listing can be reported with a reason; reports stay anonymous |

Demo persona: the signed-in user is **Mika Dela Cruz** (BS IT, 3rd year). The sample seller is **Paolo Mendoza** (CITCS).

---

## 2. Tech stack (as of this commit)

| Item | Value |
|---|---|
| Language | Kotlin |
| UI | XML layouts, Material Components, ConstraintLayout, Poppins font |
| Package | `com.nevergrowold.holdhightheshop` |
| minSdk / targetSdk / compileSdk | 24 / 37 / 37 |
| AGP | 9.4.1 |
| Dependencies | core-ktx, appcompat, activity-ktx, material, constraintlayout (no networking library yet) |
| Click handling | `android:onClick="methodName"` in XML, with methods on the Activity or on `BaseActivity` |
| Data | None. Every name, price and message is a hardcoded string in `res/values/strings.xml` |

### Shared code

- **`BaseActivity.kt`**: every screen extends it. It holds the bottom-nav handlers (`openHome`, `openMarket`, `openChats`, `openProfile`), `openCreateListing`, `openListing` and `goBack`. Tab switches use `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` so tabs don't pile up on the back stack.
- **`Choice.kt`**: single-select helpers for pills (condition), segments (Sell/Rent/Swap), chips (market filter) and radio rows (report reasons).
- **`BarInsets.kt`**: edge-to-edge padding for the status and navigation bars.

---

## 3. Screen map and navigation

```
LandingActivity (launcher, 1.6 s splash)
   └─► LoginActivity ──signIn()──► MainActivity (Home / Dashboard)
                                      │
     Bottom nav (all tabs) ◄──────────┼──────────────────────────────┐
                                      │                              │
   ┌──────────────┬───────────────────┼────────────────┬─────────────┘
   ▼              ▼                   ▼                ▼
 Market        Chats              Profile        Notifications (bell)
   │             │                   │                │
   │             └─► ChatThread      │                ├─► Chats / Listing / Profile
   │                    └─► ListingDetail (reserved strip)
   ▼
 ListingDetail ──openReport()──► ReportListing ──submitReport()──► ReportReceived
   ▲                                                                 │
   │                                                    backToListing / returnToMarket
 CreateListing (from Home card or Market FAB)
   └─postItem()──► ListingPosted ──► viewListing / backToMarket
```

---

## 4. Screen-by-screen: current state vs. finished behaviour

Legend: ✅ works now · ⚠️ partially works · ❌ visible but does nothing

### 4.1 LandingActivity (splash)
- ✅ Shows the brand, then opens Login after 1.6 s and finishes itself, so Back does not return to the splash.
- Finished: no change needed. Optionally skip straight to Main if the user is already signed in (see 4.2).

### 4.2 LoginActivity
- ✅ "Sign in securely" opens MainActivity and finishes Login.
- ❌ The email field is never validated.
- **Finished behaviour:**
  - Validate that the email is non-empty and ends with `@s.uc-bcf.edu.ph`. If not, show a **Toast**: "Use your UC student email".
  - On success, save the email in `SharedPreferences` and pass it to MainActivity with an Intent extra (`EXTRA_EMAIL`).
  - LandingActivity checks the saved email and skips Login when it is present.

### 4.3 MainActivity (Home / Dashboard)
- ✅ Bell opens Notifications. "Post an item" opens CreateListing. Item cards open ListingDetail. Bottom nav works.
- ❌ "See all →" (programs) and the four program tiles (CITCS, CONAHS, CBA, CEA) do nothing.
- ❌ Every item card opens the same Casio detail.
- **Finished behaviour:**
  - Program tiles open MarketActivity with `EXTRA_PROGRAM = "CITCS"` etc., and the Market preselects that chip.
  - "See all" and "View market" open the Market with "All" selected.
  - Each item card passes its own `EXTRA_LISTING_ID`, so the detail screen shows the right item.
  - Greeting uses the signed-in user's first name.

### 4.4 MarketActivity (Campus Market)
- ✅ Filter chips switch their selected style. Cards open detail. FAB opens CreateListing. Bottom nav works.
- ❌ Selecting a chip does not filter the cards. The search box does nothing. The filter icon, bookmark icon and "Newest" sort do nothing.
- **Finished behaviour:**
  - Chip selection hides the cards that don't match the program, and the "N items near you" count updates.
  - Search filters by title as you type (`TextWatcher`) or on the keyboard's search action.
  - Filter icon opens a dialog: listing type (Sell/Rent/Swap) and condition.
  - "Newest" opens a sort dialog: Newest, Price low→high, Price high→low.
  - Bookmark icon opens Profile on the **Saved** tab (`EXTRA_PROFILE_TAB = 2`).
  - Reads `EXTRA_PROGRAM` from the launching Intent.

### 4.5 ListingDetailActivity
- ✅ Back and Report work.
- ❌ "Reserve for ₱1,200" does nothing. The bookmark/save icon does nothing. The content is always the Casio fx-991EX.
- **Finished behaviour:**
  - Reads `EXTRA_LISTING_ID` and fills title, price, condition, description, seller and meetup from a listing store (section 6).
  - **Reserve** shows a confirm dialog. On confirm: mark the item reserved, show a Toast "Reserved! Chat with Paolo to confirm the meetup", then open ChatThreadActivity with the seller and listing IDs.
  - Save icon toggles the saved state and its icon, with a Toast "Saved" or "Removed from saved".
  - Seller row or "Message" can open ChatThread.
  - **Lab 6 API candidate:** a card showing current weather at the meetup point (section 8).

### 4.6 CreateListingActivity
- ✅ Condition pills, Sell/Rent/Swap segment, Program and Meetup pickers (AlertDialog), description counter and Cancel all work.
- ❌ **Bug:** the **Save draft** and **Post item** buttons have `clickable="true"` but no `android:onClick`. The `saveDraft()` and `postItem()` methods exist in Kotlin but nothing calls them, so the draft Toast never appears. Fix by adding `android:onClick="saveDraft"` and `android:onClick="postItem"` to the two TextViews near lines 561 and 576 of `activity_create_listing.xml`.
- ❌ The photo tiles ("Add cover" and the photo slots) do nothing. Nothing is validated before posting.
- **Finished behaviour:**
  - Photo tiles launch the system photo picker (`ActivityResultContracts.PickVisualMedia`, which needs no permission) and show the chosen image. The "0 / 4" counter updates.
  - **Post item** validates title, program, condition, price and meetup (everything marked `*`). For the first missing field it shows a **Toast** such as "Add an item title" and focuses that field.
  - When valid, it adds the listing to the store and opens ListingPosted with `EXTRA_LISTING_ID`.
  - **Save draft** stores the fields in SharedPreferences, shows the "Saved to your drafts" Toast and finishes.
  - Description is capped at 300 characters (`android:maxLength="300"`).

### 4.7 ListingPostedActivity
- ✅ "View listing" and "Back to market" work.
- **Finished behaviour:** show the real reference (for example `HTS-` plus the ID) and pass the new listing's ID on to "View listing".

### 4.8 ChatsActivity
- ✅ Rows open ChatThread. Bottom nav works.
- ❌ Every row opens Paolo's thread.
- **Finished behaviour:** each row passes `EXTRA_CHAT_NAME` (and the listing ID). ChatThread uses it for the header and the "Message Paolo…" hint.

### 4.9 ChatThreadActivity
- ✅ Back and the reserved-item strip (opens the listing) work.
- ❌ **Send** and **Attach** do nothing.
- **Finished behaviour:**
  - Send appends the typed text as an outgoing bubble (inflate `bg_bubble_out` into the message container), clears the input and scrolls to the bottom. Empty input shows a Toast "Type a message first".
  - Attach opens the photo picker, or shows a Toast "Attachments coming soon" if that is out of scope.
  - Optional implicit Intent: a "Share listing" action using `Intent.ACTION_SEND`.

### 4.10 ReportListingActivity
- ✅ Reason radio rows, counter, Back and Submit work. ✅ A **Toast** ("Select a reason first") appears if you submit with no reason. This is a valid toast for Lab 5.
- **Finished behaviour:** pass the chosen reason and listing ID to ReportReceived and show the reported item's real name.

### 4.11 ReportReceivedActivity
- ✅ "Back to listing" and "Return to market" work.

### 4.12 ProfileActivity
- ✅ Active / Completed / Saved tabs switch their underline. Item rows open the listing. Bottom nav works.
- ❌ The tabs don't change the list underneath. The "more" (⋮) icon does nothing.
- **Finished behaviour:**
  - Each tab shows its own list (show/hide three containers).
  - Reads `EXTRA_PROFILE_TAB` so Market's bookmark can open the Saved tab directly.
  - ⋮ opens a `PopupMenu` with Edit profile (Toast "Coming soon") and **Sign out**. Sign out clears SharedPreferences and opens Login with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`.

### 4.13 NotificationsActivity
- ✅ Rows open Chats, Listing or Profile. ✅ "Mark all read" shows a **Toast**.
- ❌ "Mark all read" doesn't clear the unread styling.
- **Finished behaviour:** set each row's background from `bg_row_unread` to plain and hide the unread dots, then show the Toast.

---

## 5. Intents in the finished app

### Explicit Intents (screen to screen)

| From | To | Extras | Flags |
|---|---|---|---|
| Landing | Login or Main | none | `finish()` the splash |
| Login | Main | `EXTRA_EMAIL` | `finish()` Login |
| Main | Market | `EXTRA_PROGRAM` (optional) | CLEAR_TOP / SINGLE_TOP |
| Main, Market, Profile, Notifications | ListingDetail | `EXTRA_LISTING_ID` | none |
| ListingDetail | ReportListing | `EXTRA_LISTING_ID` | none |
| ReportListing | ReportReceived | `EXTRA_LISTING_ID`, `EXTRA_REASON` | `finish()` the form |
| ListingDetail (Reserve) | ChatThread | `EXTRA_CHAT_NAME`, `EXTRA_LISTING_ID` | none |
| Chats | ChatThread | `EXTRA_CHAT_NAME`, `EXTRA_LISTING_ID` | none |
| CreateListing | ListingPosted | `EXTRA_LISTING_ID` | `finish()` the form |
| Market (bookmark) | Profile | `EXTRA_PROFILE_TAB` | CLEAR_TOP / SINGLE_TOP |
| Profile (Sign out) | Login | none | NEW_TASK / CLEAR_TASK |

Keep the extra keys in one place, for example a `companion object` in `BaseActivity` or a small `Extras.kt`:

```kotlin
object Extras {
    const val LISTING_ID = "extra_listing_id"
    const val PROGRAM = "extra_program"
    const val CHAT_NAME = "extra_chat_name"
    const val PROFILE_TAB = "extra_profile_tab"
    const val EMAIL = "extra_email"
    const val REASON = "extra_reason"
}
```

### Implicit Intents (good for showing range)

| Feature | Intent |
|---|---|
| Share a listing | `Intent.ACTION_SEND`, `type = "text/plain"`, wrapped in `Intent.createChooser` |
| Open the meetup point in Maps | `Intent.ACTION_VIEW` with `Uri.parse("geo:16.4086,120.5960?q=UC Main Gate")` |
| Email student support (Report Received) | `Intent.ACTION_SENDTO` with `mailto:` |

---

## 6. Minimal data layer (so extras mean something)

You don't need a database. One in-memory Kotlin object is enough for the labs:

```kotlin
data class Listing(
    val id: Int,
    val title: String,
    val price: Int,            // pesos
    val program: String,       // CITCS, CONAHS, CBA, CEA
    val condition: String,     // Like new, Good, Fair
    val type: String,          // Sell, Rent, Swap
    val description: String,
    val seller: String,
    val meetup: String,
    var reserved: Boolean = false,
    var saved: Boolean = false
)

object ListingStore {
    val listings = mutableListOf(
        Listing(1, "Engineering Drawing Set", 850, "CEA", "Like new", "Sell", "...", "Miguel Santos", "UC Main Gate Security Desk"),
        Listing(2, "Casio fx-991EX", 1200, "CITCS", "Good", "Sell", "...", "Paolo Mendoza", "UC Main Gate Security Desk"),
        Listing(3, "Anatomy & Physiology", 650, "CONAHS", "Good", "Sell", "...", "Janelle Garcia", "UC Library Lobby"),
        Listing(4, "UC PE Uniform", 450, "CBA", "Like new", "Rent", "...", "Aira Villanueva", "UC Canteen Area"),
        Listing(5, "White Lab Gown", 380, "CONAHS", "Good", "Rent", "...", "Janelle Garcia", "UC Library Lobby"),
        Listing(6, "Data Structures Book", 520, "CITCS", "Fair", "Swap", "...", "Paolo Mendoza", "CITCS Building Entrance")
    )
    fun get(id: Int) = listings.firstOrNull { it.id == id } ?: listings[1]
    fun add(l: Listing) { listings.add(l) }
    fun nextId() = (listings.maxOfOrNull { it.id } ?: 0) + 1
}
```

The cards in the XML stay as they are. Give each card an `android:tag="2"` (the listing ID) and change `BaseActivity.openListing(view)` to read `view.tag`:

```kotlin
fun openListing(view: View) {
    val id = (view.tag as? String)?.toIntOrNull() ?: 2
    startActivity(Intent(this, ListingDetailActivity::class.java)
        .putExtra(Extras.LISTING_ID, id))
}
```

This one change makes every existing `onClick="openListing"` pass the right item without touching each screen.

---

## 7. Lab Act 5 checklist

| Requirement | What to do | Where |
|---|---|---|
| Add Intents to features | Explicit Intents with extras (section 5), plus at least one implicit Intent (Share or Maps) | `BaseActivity`, `MainActivity`, `ListingDetailActivity`, `ChatsActivity`, `CreateListingActivity` |
| All widgets usable | Wire every ❌ item in section 4. Top priorities: Save draft/Post item (missing `onClick`), Reserve, Send, chip filtering, search, program tiles, Profile tabs, ⋮ menu | layouts and their activities |
| Add a Toast | Already present in 3 places. Pick one as the **graded** toast, and add validation toasts on Login and CreateListing | see below |
| Name the toast's activity in a comment | Put a header comment directly above the `Toast.makeText` call | see below |
| Update the Manifest | See section 7.2 | `AndroidManifest.xml` |
| MainActivity code needed by the Manifest | See section 7.3 | `MainActivity.kt` |

### 7.1 The graded Toast (suggested: CreateListingActivity)

The clearest one to demo is validation on **Post item**:

```kotlin
/*
 * LAB ACT 5: USER COMMUNICATION (TOAST)
 * Activity: CreateListingActivity
 * Trigger:  tapping "Post item" with a required field left empty.
 * Message:  tells the student which field is missing, e.g. "Add an item title".
 * Duration: Toast.LENGTH_SHORT
 */
fun postItem(view: View) {
    val title = findViewById<EditText>(R.id.inputTitle).text.toString().trim()
    if (title.isEmpty()) {
        Toast.makeText(this, "Add an item title", Toast.LENGTH_SHORT).show()
        return
    }
    // ...validate program, condition, price, meetup the same way...
    val listing = Listing(ListingStore.nextId(), title, /* ... */)
    ListingStore.add(listing)
    startActivity(Intent(this, ListingPostedActivity::class.java)
        .putExtra(Extras.LISTING_ID, listing.id))
    finish()
}
```

Remember to add `android:onClick="postItem"` and `android:onClick="saveDraft"` to the two buttons in `activity_create_listing.xml`. Without them this code never runs.

Toasts that already work and can be listed as extras: `ReportListingActivity.submitReport` ("Select a reason first") and `NotificationsActivity.markAllRead`.

### 7.2 Manifest changes

```xml
<manifest ...>

    <!-- Lab 6: needed for the weather API call. Normal permission, granted at install. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Lab 5: lets the app see Maps/Share targets on Android 11+ (package visibility) -->
    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="geo" />
        </intent>
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="text/plain" />
        </intent>
    </queries>

    <application ...>
        <!-- LandingActivity stays the LAUNCHER (MAIN + LAUNCHER intent-filter). -->

        <!-- MainActivity: launchMode singleTop so CLEAR_TOP|SINGLE_TOP tab switches
             reuse it and deliver the Intent to onNewIntent(). -->
        <activity
            android:name=".MainActivity"
            android:exported="false"
            android:launchMode="singleTop" />

        <!-- Move adjustResize to the screens that actually have keyboards -->
        <activity android:name=".CreateListingActivity" android:windowSoftInputMode="adjustResize" ... />
        <activity android:name=".ChatThreadActivity"    android:windowSoftInputMode="adjustResize" ... />
        <activity android:name=".ReportListingActivity" android:windowSoftInputMode="adjustResize" ... />
        <activity android:name=".LoginActivity"         android:windowSoftInputMode="adjustResize" ... />
    </application>
</manifest>
```

Notes:
- Every Activity is already declared. Any new Activity you add must also be declared, or the app crashes with `ActivityNotFoundException`.
- `parentActivityName` is set on each screen, which is what makes the system Up behaviour work.
- MainActivity currently carries `windowSoftInputMode="adjustResize"` even though the dashboard has no text input. It belongs on the form screens.

### 7.3 MainActivity code the Manifest relies on

Because the Manifest gives MainActivity `launchMode="singleTop"` and the tabs open it with `CLEAR_TOP | SINGLE_TOP`, a second open goes to `onNewIntent()` instead of `onCreate()`. MainActivity should handle both:

```kotlin
class MainActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        BarInsets.apply(this)
        handleIntent(intent)
    }

    // Required by launchMode="singleTop" in AndroidManifest.xml
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val email = intent.getStringExtra(Extras.EMAIL)
            ?: getSharedPreferences("session", MODE_PRIVATE).getString("email", null)
        // Use it for the greeting, e.g. "Kumusta, Mika!"
    }

    fun openNotifications(view: View) { /* existing */ }

    /** Program tiles: open the Market filtered to one college. */
    fun openProgram(view: View) {
        startActivity(Intent(this, MarketActivity::class.java)
            .putExtra(Extras.PROGRAM, view.tag as String))
    }
}
```

---

## 8. Lab Act 6: API integration

### 8.1 Recommended API: OpenWeatherMap Current Weather

**Feature:** a "Meetup weather" card on **ListingDetailActivity**, and optionally in the ChatThread meetup system message. Example text: *"Meetup at UC Main Gate Security Desk · 19 °C, light rain. Bring an umbrella."*

Why this API fits:
- It fits the app's safe-meetup idea. Every listing already has an outdoor or campus meetup point in Baguio, where rain often decides whether a handover happens.
- It requires an **API key**, which the lab asks you to include and explain.
- It is a single HTTPS GET returning small JSON. Nothing about it is flaky, so a 100% working rate is realistic.
- The free tier is generous (about 60 calls per minute, 1,000,000 per month).

**Endpoint**
```
GET https://api.openweathermap.org/data/2.5/weather
      ?lat=16.4086&lon=120.5960      (UC, Baguio City)
      &units=metric
      &appid=YOUR_API_KEY
```

**Sample response (trimmed)**
```json
{
  "weather": [{ "main": "Rain", "description": "light rain", "icon": "10d" }],
  "main": { "temp": 19.4, "feels_like": 19.6, "humidity": 88 },
  "name": "Baguio"
}
```

**Getting the key**
1. Create a free account at openweathermap.org.
2. Copy the key from *My API keys*.
3. **New keys can take up to about 2 hours to activate.** Until then the call returns `401`. Get the key well before the demo.

### 8.2 Where the key goes

The lab asks you to *include* the API key. Two options:

- **A. Graded submission (what the instructions literally ask):** put the key in a clearly marked constant with comments, as in 8.4.
- **B. Safer for a public GitHub repo:** put `OWM_API_KEY=xxxx` in `local.properties` (already git-ignored) and expose it through `BuildConfig`:

  ```kotlin
  // app/build.gradle.kts
  import java.util.Properties

  val localProps = Properties().apply {
      val f = rootProject.file("local.properties")
      if (f.exists()) f.inputStream().use { load(it) }
  }

  android {
      buildFeatures { buildConfig = true }
      defaultConfig {
          buildConfigField("String", "OWM_API_KEY", "\"${localProps.getProperty("OWM_API_KEY", "")}\"")
      }
  }
  ```
  The code then reads `BuildConfig.OWM_API_KEY`. In the comments, describe where the key lives and paste it there if the instructor needs to see it.

If this repository is public, a key committed with option A is visible to anyone. A free OpenWeatherMap key is low risk, but you can regenerate it after grading.

### 8.3 Integration process (for the comment block)

1. Add `INTERNET` and `ACCESS_NETWORK_STATE` permissions to `AndroidManifest.xml`.
2. Get an API key from OpenWeatherMap and store it (8.2).
3. Build the request URL with latitude, longitude, units and the key.
4. Run the request **off the main thread**. Android throws `NetworkOnMainThreadException` otherwise.
5. Read the HTTP status. On `200`, parse the JSON with `org.json` (built into Android, no extra dependency).
6. Switch back to the UI thread with `runOnUiThread` and update the TextViews.
7. On any failure (no internet, 401 bad key, timeout), show a fallback message and a Toast. The rest of the screen keeps working.

### 8.4 Reference implementation (no extra libraries)

`WeatherApi.kt`:

```kotlin
package com.nevergrowold.holdhightheshop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/*
 * ============================================================================
 * LAB ACT 6: API INTEGRATION
 * API:      OpenWeatherMap "Current Weather Data" (REST, JSON)
 * Docs:     https://openweathermap.org/current
 * Used in:  ListingDetailActivity, "Meetup weather" card
 *
 * HOW IT WORKS
 *  - The app sends an HTTPS GET request with the meetup point's latitude and
 *    longitude, metric units and our API key (the "appid" parameter).
 *  - OpenWeatherMap's server answers with JSON describing the current weather
 *    there: condition ("Rain"), description ("light rain"), temperature, etc.
 *  - We read only the fields we need and show them on the listing, so buyer
 *    and seller know whether to bring an umbrella or move the meetup indoors.
 *
 * API KEY
 *  - Free key from https://home.openweathermap.org/api_keys
 *  - Sent as the "appid" query parameter on every request.
 *  - Key: <PASTE YOUR KEY HERE or read it from BuildConfig.OWM_API_KEY>
 *  - A new key can take up to ~2 hours to activate (returns HTTP 401 until then).
 *
 * INTEGRATION STEPS
 *  1. Manifest: <uses-permission android:name="android.permission.INTERNET"/>
 *  2. Build the request URL (see buildUrl below).
 *  3. Run the request on a background thread (network on the main thread crashes).
 *  4. Check the HTTP status code, then parse the body with org.json.
 *  5. Return the result to the UI thread through the callback.
 *  6. On any error, return null so the screen shows a fallback message.
 * ============================================================================
 */
object WeatherApi {

    private val API_KEY = BuildConfig.OWM_API_KEY   // or "your_key_here"
    private const val BASE = "https://api.openweathermap.org/data/2.5/weather"

    data class Weather(val main: String, val description: String, val tempC: Int, val humidity: Int)

    private fun buildUrl(lat: Double, lon: Double) =
        "$BASE?lat=$lat&lon=$lon&units=metric&appid=$API_KEY"

    /** Fetches the weather in the background and calls [onResult] with it, or with null on failure. */
    fun fetch(lat: Double, lon: Double, onResult: (Weather?) -> Unit) {
        thread {
            val result = try {
                val conn = URL(buildUrl(lat, lon)).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) null
                    else {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val w = json.getJSONArray("weather").getJSONObject(0)
                        val m = json.getJSONObject("main")
                        Weather(
                            main = w.getString("main"),
                            description = w.getString("description"),
                            tempC = Math.round(m.getDouble("temp")).toInt(),
                            humidity = m.getInt("humidity")
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            onResult(result)
        }
    }

    /** A short tip shown under the weather line. */
    fun tipFor(w: Weather) = when (w.main) {
        "Rain", "Drizzle", "Thunderstorm" -> "Bring an umbrella or meet at a covered spot."
        "Fog", "Mist" -> "Low visibility. Meet near the security desk."
        else -> "Good conditions for the meetup."
    }
}
```

In `ListingDetailActivity.onCreate`:

```kotlin
val weatherText = findViewById<TextView>(R.id.meetupWeather)   // add this TextView to the meetup card
weatherText.text = "Checking meetup weather…"

WeatherApi.fetch(16.4086, 120.5960) { w ->
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        if (w == null) {
            weatherText.text = "Weather unavailable right now"
            Toast.makeText(this, "Couldn't load meetup weather", Toast.LENGTH_SHORT).show()
        } else {
            weatherText.text = "${w.tempC}°C · ${w.description}\n${WeatherApi.tipFor(w)}"
        }
    }
}
```

All four meetup points are on the UC campus in Baguio, metres apart, so one campus coordinate (`16.4086, 120.5960`, approximate) serves every listing.

### 8.5 Alternatives, if the instructor wants a different kind of API

| API | Feature in this app | Key? | Notes |
|---|---|---|---|
| **OpenWeatherMap** (recommended) | Meetup weather | Yes | Simple, reliable, fits the meetup idea |
| ImgBB / Imgur upload | Upload listing photos from CreateListing | Yes | It *sends* data, which the lab mentions, but it needs multipart upload and the photo picker. More moving parts |
| Google Gemini / an LLM API | "Write description for me" on CreateListing | Yes | Impressive, but quota limits and paid tiers make 100% uptime harder |
| ExchangeRate-API | Show price in USD | Yes | Works, but weak fit for a peso-only campus market |
| Google Maps Static API | Map image of the meetup point | Yes, plus billing account | Needs a credit card on file |

---

## 9. End-to-end demo script (what "ready to ship" means)

Run this on a fresh install. Every step should work with no crash:

1. Launch the app. The splash shows, then Login.
2. Enter a non-UC email and tap Sign in. **Toast** "Use your UC student email".
3. Enter a UC email and sign in. The dashboard greets Mika.
4. Tap the **CITCS** tile. The Market opens with CITCS selected and only CITCS items visible.
5. Type "casio" in search. Only the Casio card remains.
6. Open the Casio card. The detail shows the right item, and the **meetup weather loads from the API**.
7. Tap the save icon. **Toast** "Saved", and the icon fills.
8. Tap Reserve, then confirm. **Toast**, and ChatThread opens with Paolo.
9. Type a message and tap Send. A new bubble appears and the input clears.
10. Go Back to the detail, tap Report, then Submit without a reason. **Toast** "Select a reason first".
11. Pick a reason and submit. Report received. Tap Return to market.
12. Tap the FAB (Post) and tap Post item with the form empty. **Toast** naming the missing field.
13. Fill everything, including a photo, and post. The Listing posted screen opens. View listing shows *your* item.
14. Go to Profile, Saved tab. The Casio is listed.
15. Open Notifications from the bell and tap Mark all read. The unread styling clears and a **Toast** shows.
16. Profile ⋮, Sign out. Login opens, and Back exits the app.
17. Turn on airplane mode and open any listing. The weather card shows "unavailable" and the app does not crash.

---

## 10. Out of scope (state it in the report if asked)

- Real authentication and email verification (sign-in is simulated)
- A backend or database: listings live in memory and reset when the app restarts
- Real-time chat between devices
- Payments: transactions happen in person at the meetup point
