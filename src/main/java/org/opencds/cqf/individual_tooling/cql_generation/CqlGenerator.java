package org.opencds.cqf.individual_tooling.cql_generation;

import java.net.URI;

public interface CqlGenerator {
    public void generate(String encoding);
    public void generate(URI encodingUri);
}