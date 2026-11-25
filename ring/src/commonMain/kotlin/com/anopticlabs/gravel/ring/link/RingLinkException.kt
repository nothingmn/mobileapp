package com.anopticlabs.gravel.ring.link

import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import com.anopticlabs.gravel.ring.sync.ringLinkFailure

/**
 * The link to the ring failed. [status] is the status Android reported, when it reported one; [failure] is what that
 * status calls for, read by the runner's retry rules.
 *
 * In commonMain because both sides of the link throw it: the Android GATT layer when a connect, a write or a
 * subscription fails, and the keeper that holds the link between presses when a connect outlasts the time it
 * allows.
 */
class RingLinkException(
    val status: Int?,
    cause: Throwable?,
    val failure: RingLinkFailure = ringLinkFailure(status),
) : Exception("ring link failed: $failure, status $status", cause)
