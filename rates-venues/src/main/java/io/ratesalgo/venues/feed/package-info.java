/**
 * The SBE binary book feed (API_SPEC.md Section 10.5): {@link
 * io.ratesalgo.venues.feed.BookFeedPublisher} writes {@code BookUpdate} messages with a venue
 * sequence number and serves snapshots; {@link io.ratesalgo.venues.feed.BookFeedConsumer} rebuilds
 * the book, detects gaps and recovers by snapshot. No QuickFIX/J in this package.
 */
package io.ratesalgo.venues.feed;
