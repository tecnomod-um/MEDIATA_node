package org.taniwha.util;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.taniwha.model.Dataset;
import org.taniwha.model.Distribution;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.Variable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DCatUtilTest {

    @Test
    void readModel_andParseNodeMetadata_coverFallbackAndRichMetadata() {
        String ttl = """
                @prefix dcat: <http://www.w3.org/ns/dcat#> .
                @prefix dct: <http://purl.org/dc/terms/> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                @prefix vcard: <http://www.w3.org/2006/vcard/ns#> .
                @prefix prov: <http://www.w3.org/ns/prov#> .
                @prefix ex: <http://example.org/ns#> .

                <http://example.org/ds1> a dcat:Dataset ;
                  dct:title "Dataset One" ;
                  dct:description "A richer dataset" ;
                  dcat:keyword "k1", "k2" ;
                  ex:hasPersonalData "true" ;
                  ex:isStructured "1" ;
                  ex:numberOfRecords "42" ;
                  ex:numberOfUniqueIndividuals "not-a-number" ;
                  ex:applicableLegislation <http://example.org/law/1>, <http://example.org/law/2> ;
                  dct:publisher <http://example.org/publisher/1> ;
                  dcat:contactPoint [
                    vcard:fn "Help Desk" ;
                    vcard:hasEmail <mailto:help@example.org>
                  ] ;
                  dcat:distribution [
                    dct:title "CSV export" ;
                    dcat:accessURL <http://example.org/access> ;
                    ex:distributionExtra "dist-extra"
                  ] ;
                  ex:variables [
                    ex:name "age" ;
                    ex:dataType "integer" ;
                    ex:extra "var-extra"
                  ] ;
                  prov:wasGeneratedBy [
                    dct:title "Pipeline"
                  ] ;
                  ex:extra "dataset-extra" .

                <http://example.org/publisher/1>
                  foaf:name "Publisher One" ;
                  foaf:homepage <http://example.org/publisher/home> .
                """;

        Model model = DCatUtil.readModel(ttl, "catalog.json");
        NodeMetadata metadata = DCatUtil.parseNodeMetadata(model, "catalog.json");

        assertThat(metadata.getContext()).isEqualTo("https://www.w3.org/ns/dcat.jsonld");
        assertThat(metadata.getType()).isEqualTo("dcat:Catalog");
        assertThat(metadata.getSourceFile()).isEqualTo("catalog.json");

        Dataset dataset = metadata.getDataset().get(0);
        assertThat(dataset.getUri()).isEqualTo("http://example.org/ds1");
        assertThat(dataset.getTitle()).isEqualTo("Dataset One");
        assertThat(dataset.getDescription()).isEqualTo("A richer dataset");
        assertThat(dataset.getKeyword()).containsExactlyInAnyOrder("k1", "k2");
        assertThat(dataset.getHasPersonalData()).isTrue();
        assertThat(dataset.getIsStructured()).isTrue();
        assertThat(dataset.getNumberOfRecords()).isEqualTo(42L);
        assertThat(dataset.getNumberOfUniqueIndividuals()).isNull();
        assertThat(dataset.getApplicableLegislation())
                .containsExactlyInAnyOrder("http://example.org/law/1", "http://example.org/law/2");
        assertThat(dataset.getAdditionalProperties()).containsEntry("extra", "dataset-extra");

        assertThat(dataset.getPublisher()).isInstanceOfSatisfying(Map.class, publisher ->
                assertThat(publisher)
                        .containsEntry("uri", "http://example.org/publisher/1")
                        .containsEntry("foaf:name", "Publisher One")
                        .containsEntry("foaf:homepage", "http://example.org/publisher/home"));

        assertThat(dataset.getContactPoint()).isInstanceOfSatisfying(Map.class, contactPoint ->
                assertThat(contactPoint)
                        .containsEntry("vcard:fn", "Help Desk")
                        .containsEntry("vcard:hasEmail", "mailto:help@example.org"));

        assertThat(dataset.getWasGeneratedBy()).isInstanceOfSatisfying(Map.class, generatedBy ->
                assertThat(generatedBy).containsEntry("dct:title", "Pipeline"));

        List<Distribution> distributions = dataset.getDistribution();
        assertThat(distributions).hasSize(1);
        Distribution distribution = distributions.get(0);
        assertThat(distribution.getTitle()).isEqualTo("CSV export");
        assertThat(distribution.getAccessURL()).isEqualTo("http://example.org/access");
        assertThat(distribution.getAdditionalProperties()).containsEntry("distributionExtra", "dist-extra");

        List<Variable> variables = dataset.getVariables();
        assertThat(variables).hasSize(1);
        Variable variable = variables.get(0);
        assertThat(variable.getName()).isEqualTo("age");
        assertThat(variable.getDataType()).isEqualTo("integer");
        assertThat(variable.getAdditionalProperties()).containsEntry("extra", "var-extra");
    }
}
