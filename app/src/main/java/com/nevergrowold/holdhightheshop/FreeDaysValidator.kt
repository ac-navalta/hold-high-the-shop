package com.nevergrowold.holdhightheshop

import android.os.Parcel
import android.os.Parcelable
import com.google.android.material.datepicker.CalendarConstraints
import java.util.Calendar
import java.util.TimeZone

/**
 * Date picker rule: only the weekdays in [days] can be picked
 * (0 = Monday ... 4 = Friday), so the calendar greys out the rest.
 */
class FreeDaysValidator(private val days: IntArray) : CalendarConstraints.DateValidator {

    override fun isValid(date: Long): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = date }
        return Availability.weekdayOf(cal.get(Calendar.DAY_OF_WEEK)) in days
    }

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeIntArray(days)

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<FreeDaysValidator> {
            override fun createFromParcel(source: Parcel) = FreeDaysValidator(source.createIntArray() ?: IntArray(0))
            override fun newArray(size: Int) = arrayOfNulls<FreeDaysValidator>(size)
        }
    }
}
