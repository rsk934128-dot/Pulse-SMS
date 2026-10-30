package com.example.booking

import com.squareup.moshi.JsonClass

/**
 * Data models for Booking.com API integration.
 */
@JsonClass(generateAdapter = true)
data class BookingHotel(
    val hotelId: Long,
    val name: String,
    val destination: String,
    val reviewScore: Double,
    val reviewScoreWord: String,
    val starRating: Int,
    val pricePerNight: String,
    val currency: String = "BDT",
    val photoUrl: String,
    val address: String,
    val bookingUrl: String,
    val badge: String? = null
)

@JsonClass(generateAdapter = true)
data class BookingReservation(
    val confirmationNumber: String,
    val pinCode: String,
    val hotelName: String,
    val checkInDate: String,
    val checkOutDate: String,
    val status: String = "CONFIRMED",
    val totalPrice: String? = null,
    val currency: String = "BDT"
)

data class BookingSearchResult(
    val query: String,
    val hotels: List<BookingHotel>,
    val isFromCache: Boolean = false,
    val source: String = "Booking.com Live API"
)
