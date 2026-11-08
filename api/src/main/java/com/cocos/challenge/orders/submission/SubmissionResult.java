package com.cocos.challenge.orders.submission;

import com.cocos.challenge.orders.StoredOrder;

/** The order of a submission, and whether it is the answer to a retry: nothing new was recorded. */
public record SubmissionResult(StoredOrder order, boolean replayed) {}
