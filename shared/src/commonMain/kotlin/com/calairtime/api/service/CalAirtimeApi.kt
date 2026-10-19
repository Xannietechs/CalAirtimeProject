package com.calairtime.api.service

import com.calairtime.api.model.ConsumerCheckApiRequest
import com.calairtime.api.model.ConsumerCheckResponse

interface CalAirtimeApi {
    suspend fun consumerCheck(
        request: ConsumerCheckApiRequest
    ): ConsumerCheckResponse
}