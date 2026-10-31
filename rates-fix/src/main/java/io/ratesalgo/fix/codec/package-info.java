/**
 * Allocation-free FIX 4.4 tag-value codec over Agrona buffers: {@link
 * io.ratesalgo.fix.codec.FixParser} indexes a framed message in place, {@link
 * io.ratesalgo.fix.codec.FixWriter} encodes one, {@link io.ratesalgo.fix.codec.FixFramer} finds
 * message boundaries in a byte stream. Hot-path package: no allocation after construction except
 * on malformed input, where a {@link io.ratesalgo.fix.codec.FixFormatException} is thrown.
 */
package io.ratesalgo.fix.codec;
