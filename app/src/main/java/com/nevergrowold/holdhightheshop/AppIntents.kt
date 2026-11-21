package com.nevergrowold.holdhightheshop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import java.io.File

/**
 * Centralised intent builders, deep-link parser, and safe implicit-intent
 * launchers for Hold High The Shop.
 *
 * Covers every outgoing implicit intent the app supports:
 * - Share listing / seller profile / receipt / app (ACTION_SEND)
 * - External maps directions to UC Baguio meetup points (ACTION_VIEW geo: / https:)
 * - Calendar reminders for meetups and loan return dates (ACTION_INSERT CalendarContract)
 * - Gallery photo picker (ACTION_GET_CONTENT for images) and camera capture (ACTION_IMAGE_CAPTURE)
 * - External photo viewer (ACTION_VIEW for images)
 * - Email student support (ACTION_SENDTO mailto:)
 * - Dial UC Campus Security Desk (ACTION_DIAL tel:)
 * - Open University of the Cordilleras website (ACTION_VIEW https:)
 * - Open Android OS notification settings (ACTION_APP_NOTIFICATION_SETTINGS)
 *
 * Also parses incoming deep links declared in AndroidManifest.xml on [MainActivity].
 */
object AppIntents {

    const val WEB_HOST = "holdhightheshop.app"
    const val APP_SCHEME = "holdhightheshop"
    const val SUPPORT_EMAIL = "support@holdhightheshop.app"
    const val UC_SECURITY_PHONE = "+63744423316"
    const val UC_WEBSITE_URL = "https://www.uc-bcf.edu.ph"

    /** Extra keys for incoming ACTION_SEND content forwarded from [MainActivity] to [CreateListingActivity]. */
    const val EXTRA_SHARED_SUBJECT = "shared_subject"
    const val EXTRA_SHARED_TEXT = "shared_text"
    const val EXTRA_SHARED_IMAGE_URI = "shared_image_uri"

    /** Approximate coordinates of the four UC Baguio Safe Meetup Points (Gov. Pack Rd., Baguio City). */
    private val MEETUP_COORDS = mapOf(
        "UC Main Gate Security Desk" to Pair(16.40880, 120.59790),
        "UC Library Lobby" to Pair(16.40905, 120.59755),
        "CITCS Building Entrance" to Pair(16.40920, 120.59825),
        "UC Canteen Area" to Pair(16.40865, 120.59815),
    )

    sealed class DeepLinkRoute {
        data class ListingDetail(val listingId: String) : DeepLinkRoute()
        data class Market(val program: String? = null, val query: String? = null) : DeepLinkRoute()
        object Chats : DeepLinkRoute()
        data class ChatThread(val sellerName: String, val listingId: String? = null) : DeepLinkRoute()
        data class Profile(val subTab: Int = ProfileActivity.TAB_ACTIVE) : DeepLinkRoute()
        data class SellerProfile(val sellerName: String) : DeepLinkRoute()
        data class MapDirections(val place: String) : DeepLinkRoute()
        object CreateListing : DeepLinkRoute()
        object Saved : DeepLinkRoute()
        object Notifications : DeepLinkRoute()
        object Settings : DeepLinkRoute()
    }

    /**
     * Parses both web deep links (`https://holdhightheshop.app/listing/item2`)
     * and custom-scheme links (`holdhightheshop://listing/item2`).
     */
    fun parseDeepLink(uri: Uri?): DeepLinkRoute? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val segments = uri.pathSegments.orEmpty()

        val section: String
        val arg: String?
        when {
            scheme == APP_SCHEME -> {
                section = uri.host?.lowercase() ?: return null
                arg = segments.firstOrNull()
            }
            (scheme == "https" || scheme == "http") && uri.host.equals(WEB_HOST, ignoreCase = true) -> {
                section = segments.firstOrNull()?.lowercase() ?: return null
                arg = segments.getOrNull(1)
            }
            else -> return null
        }

        val decodedArg = arg?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }
        return when (section) {
            "listing" -> DeepLinkRoute.ListingDetail(decodedArg ?: Listings.DEFAULT_ID)
            "market" -> {
                val program = (decodedArg ?: uri.getQueryParameter("program"))?.uppercase()
                    ?.takeIf { it in Listings.PROGRAMS }
                val query = uri.getQueryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }
                DeepLinkRoute.Market(program = program, query = query)
            }
            "chats" -> DeepLinkRoute.Chats
            "chat" -> {
                val seller = decodedArg ?: uri.getQueryParameter("seller")
                if (seller.isNullOrBlank()) DeepLinkRoute.Chats
                else DeepLinkRoute.ChatThread(seller, uri.getQueryParameter("listing"))
            }
            "profile" -> {
                val subTab = when (decodedArg?.lowercase()) {
                    "completed" -> ProfileActivity.TAB_COMPLETED
                    "saved" -> ProfileActivity.TAB_SAVED
                    else -> ProfileActivity.TAB_ACTIVE
                }
                DeepLinkRoute.Profile(subTab)
            }
            "seller" -> DeepLinkRoute.SellerProfile(decodedArg ?: Listings.SELLERS.first().name)
            "map" -> DeepLinkRoute.MapDirections(decodedArg ?: Listings.MEETUP_POINTS.first())
            "create" -> DeepLinkRoute.CreateListing
            "saved" -> DeepLinkRoute.Saved
            "notifications" -> DeepLinkRoute.Notifications
            "settings" -> DeepLinkRoute.Settings
            else -> null
        }
    }

    // ---------------------------------------------------------------- share (ACTION_SEND)

    fun listingUrl(listingId: String): String = "https://$WEB_HOST/listing/$listingId"

    fun sellerUrl(sellerName: String): String = "https://$WEB_HOST/seller/${Uri.encode(sellerName)}"

    fun shareTextIntent(subject: String, text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }

    fun shareListingChooser(context: Context, listing: Listing): Intent {
        val price = Listings.priceLabel(context, listing).toString()
        val body = context.getString(
            R.string.share_listing_body,
            listing.title,
            price,
            listing.meetup,
            listingUrl(listing.id),
        )
        return Intent.createChooser(
            shareTextIntent(listing.title, body),
            context.getString(R.string.share_chooser_title),
        )
    }

    fun shareSellerChooser(context: Context, seller: Seller): Intent {
        val body = context.getString(
            R.string.share_seller_body,
            seller.name,
            seller.program,
            seller.year,
            sellerUrl(seller.name),
        )
        return Intent.createChooser(
            shareTextIntent(seller.name, body),
            context.getString(R.string.share_profile_chooser_title),
        )
    }

    fun shareReceiptChooser(context: Context, exchange: Exchange, reference: String): Intent {
        val partner = exchange.partner?.name ?: context.getString(R.string.ex_closed_by_you)
        val body = context.getString(
            R.string.share_receipt_body,
            exchange.title,
            Listings.price(exchange.price),
            reference,
            partner,
            exchange.meetup,
            ExchangeText.date(context, exchange),
        )
        return Intent.createChooser(
            shareTextIntent(context.getString(R.string.share_receipt_subject, reference), body),
            context.getString(R.string.share_receipt_chooser_title),
        )
    }

    fun shareAppChooser(context: Context): Intent {
        val body = context.getString(R.string.share_app_body, "https://$WEB_HOST/market")
        return Intent.createChooser(
            shareTextIntent(context.getString(R.string.app_name), body),
            context.getString(R.string.share_app_chooser_title),
        )
    }

    // ---------------------------------------------------------------- maps (ACTION_VIEW geo:)

    fun mapGeoIntent(place: String): Intent {
        val (lat, lng) = MEETUP_COORDS[place] ?: MEETUP_COORDS.values.first()
        val label = Uri.encode("$place, University of the Cordilleras, Baguio")
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)"))
    }

    fun mapWebIntent(place: String): Intent {
        val (lat, lng) = MEETUP_COORDS[place] ?: MEETUP_COORDS.values.first()
        val query = Uri.encode("$place, University of the Cordilleras, Baguio")
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$query&ll=$lat,$lng"))
    }

    // ---------------------------------------------------------------- calendar (ACTION_INSERT)

    fun calendarEventIntent(
        title: String,
        location: String,
        description: String,
        beginMillis: Long,
        endMillis: Long = beginMillis + 30 * 60 * 1000L,
    ): Intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.Events.EVENT_LOCATION, "$location, University of the Cordilleras")
        putExtra(CalendarContract.Events.DESCRIPTION, description)
    }

    // ---------------------------------------------------------------- photos (gallery / camera / viewer)

    fun pickImageIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = "image/*"
        addCategory(Intent.CATEGORY_OPENABLE)
    }

    fun capturePhotoIntent(context: Context): Pair<Intent, Uri> {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent to uri
    }

    fun viewImageIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ---------------------------------------------------------------- support, security & system settings

    fun emailSupportIntent(context: Context, subject: String, body: String): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

    fun dialSecurityIntent(): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$UC_SECURITY_PHONE"))

    fun openWebsiteIntent(url: String = UC_WEBSITE_URL): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))

    fun appNotificationSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }

    // ---------------------------------------------------------------- safe launcher

    /**
     * Launches an implicit [intent] safely. If no handler exists on the device,
     * tries [fallback] if provided, otherwise shows a Toast explaining that no
     * compatible app is installed.
     */
    fun launch(
        context: Context,
        intent: Intent,
        fallback: Intent? = null,
        unavailableToastRes: Int = R.string.toast_no_app_available,
    ): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (fallback != null) {
                try {
                    context.startActivity(fallback)
                    return true
                } catch (_: ActivityNotFoundException) {
                    // Fall through to Toast below.
                }
            }
            Toast.makeText(context, unavailableToastRes, Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Safely decodes a local photo URI into [target], toggling [placeholder] visibility.
     * Decoding up front via ContentResolver prevents lazy SecurityException/IOException
     * crashes during ImageView.onMeasure when a persisted URI is no longer readable.
     */
    fun bindImageUri(target: ImageView, placeholder: View?, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) {
            target.setImageDrawable(null)
            target.isVisible = false
            placeholder?.isVisible = true
            return false
        }
        return try {
            val uri = Uri.parse(uriString)
            val bitmap = target.context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
            if (bitmap != null) {
                target.setImageBitmap(bitmap)
                target.isVisible = true
                placeholder?.isVisible = false
                true
            } else {
                target.setImageDrawable(null)
                target.isVisible = false
                placeholder?.isVisible = true
                false
            }
        } catch (_: Exception) {
            target.setImageDrawable(null)
            target.isVisible = false
            placeholder?.isVisible = true
            false
        }
    }
}
