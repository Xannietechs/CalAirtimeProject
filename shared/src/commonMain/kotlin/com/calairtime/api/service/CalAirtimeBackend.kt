package com.calairtime.api.service

import com.calairtime.api.model.ConsumerCheckApiRequest
import com.calairtime.api.model.ConsumerCheckResponse

interface CalAirtimeBackend {

    suspend fun consumerCheck(
        request: ConsumerCheckApiRequest
    ): ConsumerCheckResponse
}