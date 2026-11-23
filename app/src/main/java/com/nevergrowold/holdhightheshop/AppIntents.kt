package com.nevergrowold.holdhightheshop

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nevergrowold.holdhightheshop.media.ImagePipeline
import java.io.File

/**
 * Centralised intent builders, deep-link parser, and safe implicit-intent
 * launchers for Hold High The Shop.
 *
 * Outgoing implicit intents supported:
 * - Share listing / seller profile / receipt / app (`ACTION_SEND`)
 * - Calendar reminders for campus meetups & loan return dates (`ACTION_INSERT CalendarContract`)
 * - Zero-permission Photo Picker (`PickVisualMedia`/`PickMultipleVisualMedia`) + Camera (`ACTION_IMAGE_CAPTURE` via scoped `FileProvider`)
 * - External photo viewer (`ACTION_VIEW` with temporary `FLAG_GRANT_READ_URI_PERMISSION`)
 * - Open email app for UC Outlook verification (`ACTION_MAIN` + `CATEGORY_APP_EMAIL`)
 * - Email student support (`ACTION_SENDTO mailto:`)
 * - Dial UC Campus Security Desk (`ACTION_DIAL tel:`)
 * - Open University of the Cordilleras website (`ACTION_VIEW https:`)
 * - Open Android OS notification & app settings (`ACTION_APP_NOTIFICATION_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS`)
 *
 * Security hardening:
 * - Web deep links require `https://` strictly (no cleartext `http://`).
 * - Removed legacy placeholder `/map` deep link and external `geo:` map intents.
 */
object AppIntents {

    const val WEB_HOST = "holdhightheshop.app"
    const val APP_SCHEME = "holdhightheshop"
    const val SUPPORT_EMAIL = "support@holdhightheshop.app"
    const val UC_SECURITY_PHONE = "+63744423316"
    const val UC_WEBSITE_URL = "https://www.uc-bcf.edu.ph"

    /** Extra keys for incoming sanitized ACTION_SEND content forwarded to [CreateListingActivity]. */
    const val EXTRA_SHARED_SUBJECT = "shared_subject"
    const val EXTRA_SHARED_TEXT = "shared_text"
    const val EXTRA_SHARED_IMAGE_URI = "shared_image_uri"

    sealed class DeepLinkRoute {
        data class ListingDetail(val listingId: String) : DeepLinkRoute()
        data class Market(val program: String? = null, val query: String? = null) : DeepLinkRoute()
        object Chats : DeepLinkRoute()
        data class ChatThread(val sellerName: String, val listingId: String? = null) : DeepLinkRoute()
        data class Profile(val subTab: Int = ProfileActivity.TAB_ACTIVE) : DeepLinkRoute()
        data class SellerProfile(val sellerName: String) : DeepLinkRoute()
        object CreateListing : DeepLinkRoute()
        object Saved : DeepLinkRoute()
        object Notifications : DeepLinkRoute()
        object Settings : DeepLinkRoute()
    }

    /**
     * Parses strictly HTTPS web deep links (`https://holdhightheshop.app/listing/item2`)
     * and custom-scheme links (`holdhightheshop://listing/item2`).
     * Cleartext `http://` links and removed `/map` routes are explicitly rejected.
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
            scheme == "https" && uri.host.equals(WEB_HOST, ignoreCase = true) -> {
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
            "seller" -> DeepLinkRoute.SellerProfile(decodedArg ?: Listings.SELLERS.firstOrNull()?.name ?: "")
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

    // ---------------------------------------------------------------- photos (camera / viewer)

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

    /** Opens a `media://` or `content://` image reference safely in an external image viewer. */
    fun viewMediaRef(context: Context, ref: String): Boolean {
        val uri = ImagePipeline.viewableUri(context, ref) ?: return false
        return launch(context, viewImageIntent(uri), unavailableToastRes = R.string.toast_no_gallery_app)
    }

    // ---------------------------------------------------------------- email, support, security & settings

    /** Opens the device's email client (e.g. Outlook / Gmail) so the student can click their UC verification link. */
    fun openEmailAppIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_EMAIL)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

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
            appDetailsSettingsIntent(context)
        }

    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    // ---------------------------------------------------------------- safe launcher & async photo bind

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
                    // Fall through to Toast.
                }
            }
            Toast.makeText(context, unavailableToastRes, Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Off-main-thread image binding via [ImagePipeline.bindAsync] (eliminates main-thread
     * bitmap decoding ANRs & supports both `media://` Firestore-synced WebP and `content://`).
     */
    fun bindImageUri(target: ImageView, placeholder: View?, uriString: String?): Boolean {
        ImagePipeline.bindAsync(target, placeholder, uriString)
        return !uriString.isNullOrBlank()
    }
}
