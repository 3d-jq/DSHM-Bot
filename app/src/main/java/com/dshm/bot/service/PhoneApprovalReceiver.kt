package com.dshm.bot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PhoneApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        PhoneApprovalGate.complete(id, intent.getBooleanExtra(EXTRA_ALLOW, false))
    }

    companion object {
        const val EXTRA_ID = "phone-approval-id"
        const val EXTRA_ALLOW = "phone-approval-allow"
    }
}
