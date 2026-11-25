package com.nevergrowold.holdhightheshop.data

/**
 * Domain models for real-time multi-device chats, interactive schedule &
 * return-extension proposals, dynamic notifications, announcements, and
 * live meetup countdowns.
 */
object ListingStatus {
    const val OPEN = "OPEN"
    const val PAUSED = "PAUSED"
    const val RESERVED = "RESERVED"
    const val ON_LOAN = "ON_LOAN"
    const val CLOSED = "CLOSED"
    const val UNDER_REVIEW = "UNDER_REVIEW"
    const val REMOVED_BY_ADMIN = "REMOVED_BY_ADMIN"
}

object AiCheckStatus {
    const val APPROVED = "APPROVED"
    const val PENDING_REVIEW = "PENDING_REVIEW"
    const val REJECTED = "REJECTED"
}

object ProposalType {
    const val MEETUP_CHANGE = "MEETUP_CHANGE"
    const val RETURN_EXTENSION = "RETURN_EXTENSION"
}

object ProposalStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val DECLINED = "DECLINED"
    const val WITHDRAWN = "WITHDRAWN"
    const val EXPIRED = "EXPIRED"
}

object NotificationType {
    const val MESSAGE = "MESSAGE"
    const val RESERVATION = "RESERVATION"
    const val REQUEST = "REQUEST"
    const val LOAN = "LOAN"
    const val RATING = "RATING"
    const val REPORT = "REPORT"
    const val ANNOUNCEMENT = "ANNOUNCEMENT"
    const val MODERATION = "MODERATION"
}

object NotificationTarget {
    const val LISTING = "LISTING"
    const val CHAT = "CHAT"
    const val EXCHANGE = "EXCHANGE"
    const val PROFILE_COMPLETED = "PROFILE_COMPLETED"
    const val ANNOUNCEMENT = "ANNOUNCEMENT"
    const val NONE = "NONE"
}

data class ProposalPayload(
    val proposalId: String,
    val proposalType: String, // ProposalType.MEETUP_CHANGE or RETURN_EXTENSION
    val proposalStatus: String = ProposalStatus.PENDING,
    val proposerUid: String,
    val proposerName: String,
    val listingId: String,
    val requestId: String? = null,
    val oldMeetup: String? = null,
    val oldMeetMillis: Long? = null,
    val newMeetup: String? = null,
    val newMeetupNote: String = "",
    val newMeetMillis: Long? = null,
    val extraDays: Int = 0,
    val extraCostPesos: Int = 0,
    val newDueMillis: Long? = null,
    val note: String = "",
    val resolvedByUid: String? = null,
    val resolvedAt: Long? = null,
)

data class ChatThread(
    val threadId: String,
    val listingId: String,
    val ownerUid: String,
    val buyerUid: String,
    val ownerName: String,
    val buyerName: String,
    val ownerProgram: String,
    val buyerProgram: String,
    val lastMessagePreview: String = "",
    val lastMessageMillis: Long = System.currentTimeMillis(),
    val lastSenderUid: String = "",
    val unreadByUid: Map<String, Boolean> = emptyMap(),
) {
    fun partnerUid(myUid: String): String = if (myUid == ownerUid) buyerUid else ownerUid
    fun partnerName(myUid: String): String = if (myUid == ownerUid) buyerName else ownerName
    fun partnerProgram(myUid: String): String = if (myUid == ownerUid) buyerProgram else ownerProgram
    fun isUnreadFor(myUid: String): Boolean = unreadByUid[myUid] == true

    companion object {
        /** Per-listing deterministic thread ID so multiple deals with the same student stay separate. */
        fun idFor(listingId: String, buyerUid: String): String = "${listingId}_${buyerUid}"
    }
}

data class AppNotification(
    val id: String,
    val recipientUid: String,
    val type: String,
    val title: String,
    val body: String,
    val targetType: String,
    val targetId: String,
    val extraArg: String? = null,
    val read: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class CampusAnnouncement(
    val id: String,
    val title: String,
    val body: String,
    val authorEmail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class ActiveMeetupCountdown(
    val listingId: String,
    val listingTitle: String,
    val partnerFirstName: String,
    val meetupPlace: String,
    val targetMillis: Long,
    val remainingMillis: Long,
    val isLoanDue: Boolean,
    val isOverdue: Boolean,
)

sealed class AuthResult {
    data class VerifiedSuccess(val uid: String, val email: String) : AuthResult()
    data class NeedsVerification(val uid: String, val email: String) : AuthResult()
    data class Suspended(val reason: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
