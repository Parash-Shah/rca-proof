package dev.rcaproof.experiment;

import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.classification.FaultClassifier;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioEvaluationTest {
    record Case(String id,String signature,String variant,String state,String minimal,String category) {}
    record Result(String id,String signature,boolean correct,boolean falseProven,boolean abstained,int trials,long nanos) {}
    static final List<Result> results=Collections.synchronizedList(new ArrayList<>());
    @TestFactory List<DynamicTest> evaluateGroundTruth() throws Exception {
        List<Case> cases;
        try (var input=getClass().getResourceAsStream("/evaluation/incidents.tsv")) {
            assertNotNull(input);
            cases=new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().skip(1).filter(s->!s.isBlank()).map(s->{
                var c=s.split("\t"); return new Case(c[0],c[1],c[2],c[3],c[4],c[5]);
            }).toList();
        }
        assertTrue(cases.size()>=30); assertTrue(cases.stream().map(Case::signature).distinct().count()>=8);
        assertEquals(cases.size(),cases.stream().map(Case::id).distinct().count());
        return cases.stream().map(c->DynamicTest.dynamicTest(c.id(),()->{
            var definition=ScenarioLab.find(c.signature()).orElseThrow();
            long start=System.nanoTime();
            var report=new CounterfactualEngine().investigate(definition.factors(),3,ScenarioLab.SCOPE,(f,r)->ScenarioLab.run(c.signature(),f,c.variant(),r).observation());
            var decision=new FaultClassifier().classify(c.signature(),report);
            var expected=c.minimal().equals("-")?List.<Set<String>>of():List.of(Set.of(c.minimal().split("\\+")));
            // Inconclusive reports may retain supported subsets; they must never be promoted to proof.
            boolean causeMatches=!c.state().equals("PROVEN") || expected.equals(report.minimalCausalSets());
            boolean correct=c.state().equals(report.state().name()) && causeMatches && c.category().equals(decision.category().name());
            boolean falseProven=report.state()==CounterfactualEngine.ProofState.PROVEN && (!c.state().equals("PROVEN") || !causeMatches);
            results.add(new Result(c.id(),c.signature(),correct,falseProven,report.state()==CounterfactualEngine.ProofState.INCONCLUSIVE,report.trials().size(),System.nanoTime()-start));
            assertAll(()->assertFalse(falseProven,"Incorrect hypothesis promoted to PROVEN"),()->assertTrue(correct,report+" / "+decision));
        })).toList();
    }
    @AfterAll static void writeMeasurements() throws Exception {
        Files.createDirectories(Path.of("target"));
        StringBuilder csv=new StringBuilder("id,signature,correct,falseProven,abstained,trials,durationNanos\n");
        results.stream().sorted(Comparator.comparing(Result::id)).forEach(r->csv.append(r.id()).append(',').append(r.signature()).append(',').append(r.correct()).append(',').append(r.falseProven()).append(',').append(r.abstained()).append(',').append(r.trials()).append(',').append(r.nanos()).append('\n'));
        Files.writeString(Path.of("target/scenario-evaluation.csv"),csv);
        var durations=results.stream().mapToLong(Result::nanos).sorted().toArray();
        String summary="{\"scope\":\"LOCAL_BEHAVIORAL_MODELS_ONLY\",\"incidents\":"+results.size()+",\"classes\":"+results.stream().map(Result::signature).distinct().count()
                +",\"correct\":"+results.stream().filter(Result::correct).count()+",\"falseProven\":"+results.stream().filter(Result::falseProven).count()
                +",\"abstained\":"+results.stream().filter(Result::abstained).count()+",\"medianDurationNanos\":"+(durations.length==0?0:durations[durations.length/2])+"}";
        Files.writeString(Path.of("target/scenario-evaluation.json"),summary);
    }
}
