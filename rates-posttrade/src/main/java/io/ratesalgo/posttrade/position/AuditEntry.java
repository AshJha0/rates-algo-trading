package io.ratesalgo.posttrade.position;

import io.ratesalgo.posttrade.trade.TradeKey;

/**
 * One line of the books-and-records audit trail. Every booking decision the keeper makes leaves
 * one, including the decisions not to book (duplicates, rejected amendments).
 *
 * @param journalSequence sequence of the frame that caused the entry, 0 for an operator call
 * @param timestampNanos the frame's timestamp
 * @param kind what happened
 * @param key the trade concerned
 * @param actor party id of the person who requested an amendment, 0 otherwise
 * @param detail human-readable before and after values
 */
public record AuditEntry(
    long journalSequence, long timestampNanos, AuditKind kind, TradeKey key, int actor, String detail) {}
