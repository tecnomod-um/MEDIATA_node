package org.taniwha.service.semanticcde;

import java.io.IOException;

public interface SemanticCdeProcessorClient {
    SemanticCdeProcessorResult execute(SemanticCdeProcessorCommand command) throws IOException, InterruptedException;
}
