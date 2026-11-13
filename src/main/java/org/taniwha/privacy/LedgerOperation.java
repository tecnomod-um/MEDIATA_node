package org.taniwha.privacy;

import java.io.IOException;

@FunctionalInterface
interface LedgerOperation<T> {
    T run() throws IOException;
}
