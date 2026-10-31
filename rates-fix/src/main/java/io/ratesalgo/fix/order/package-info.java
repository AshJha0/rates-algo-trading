/**
 * Engine-side order state kept from execution reports: duplicate ExecIDs dropped, stale status
 * reports that would move an order backwards dropped, late fills after a cancel applied and
 * counted, busted trades reversed. Hot-path package: fixed capacity, no allocation per message.
 */
package io.ratesalgo.fix.order;
