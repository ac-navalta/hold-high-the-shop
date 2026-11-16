package com.nevergrowold.holdhightheshop

import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Directions to a Safe Meetup Point, opened from the meetup sheet. Uses a
 * placeholder campus picture you can pinch, drag and double-tap to zoom.
 * A real map and live location come later.
 */
class MapActivity : BaseActivity() {

    private lateinit var map: CampusMapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_map)
        BarInsets.apply(this)

        val place = intent.getStringExtra(EXTRA_PLACE) ?: Listings.MEETUP_POINTS[0]
        val route = ROUTES[place] ?: ROUTES.getValue(Listings.MEETUP_POINTS[0])

        map = findViewById(R.id.campusMap)
        map.route = route
        map.contentDescription = getString(R.string.map_cd, place)

        findViewById<TextView>(R.id.mapPlace).text = place
        val meters = route.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum() * METERS_PER_PIXEL
        val minutes = ceil(meters / WALK_METERS_PER_MINUTE).toInt().coerceAtLeast(1)
        findViewById<TextView>(R.id.mapWalk).text =
            resources.getQuantityString(R.plurals.map_walk, minutes, minutes)
    }

    fun zoomIn(view: View) = map.zoomBy(1.5f)

    fun zoomOut(view: View) = map.zoomBy(1 / 1.5f)

    fun recenter(view: View) = map.recenter()

    companion object {
        const val EXTRA_PLACE = "place"

        /** Rough size of the placeholder picture: 1600 px is about 300 m. */
        private const val METERS_PER_PIXEL = 300f / 1600f
        private const val WALK_METERS_PER_MINUTE = 70f

        /** From the Main Gate along the walkways, in campus_map.png pixels. */
        private val ROUTES = mapOf(
            "UC Main Gate Security Desk" to listOf(PointF(800f, 1440f), PointF(800f, 1415f), PointF(855f, 1415f)),
            "UC Library Lobby" to listOf(PointF(800f, 1440f), PointF(800f, 820f), PointF(380f, 820f)),
            "CITCS Building Entrance" to listOf(PointF(800f, 1440f), PointF(800f, 560f), PointF(1190f, 560f)),
            "UC Canteen Area" to listOf(PointF(800f, 1440f), PointF(800f, 1080f), PointF(1210f, 1080f))
        )
    }
}
