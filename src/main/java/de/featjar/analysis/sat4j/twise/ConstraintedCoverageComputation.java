/*
 * Copyright (C) 2025 FeatJAR-Development-Team
 *
 * This file is part of FeatJAR-formula-analysis-sat4j.
 *
 * formula-analysis-sat4j is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 *
 * formula-analysis-sat4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with formula-analysis-sat4j. If not, see <https://www.gnu.org/licenses/>.
 *
 * See <https://github.com/FeatureIDE/FeatJAR-formula-analysis-sat4j> for further information.
 */
package de.featjar.analysis.sat4j.twise;

import de.featjar.analysis.RuntimeContradictionException;
import de.featjar.analysis.RuntimeTimeoutException;
import de.featjar.analysis.sat4j.computation.MIGBuilder;
import de.featjar.analysis.sat4j.solver.ISelectionStrategy;
import de.featjar.analysis.sat4j.solver.MIGVisitorByte;
import de.featjar.analysis.sat4j.solver.ModalImplicationGraph;
import de.featjar.analysis.sat4j.solver.SAT4JSolutionSolver;
import de.featjar.analysis.sat4j.solver.SAT4JSolver;
import de.featjar.base.computation.Computations;
import de.featjar.base.computation.Dependency;
import de.featjar.base.computation.IComputation;
import de.featjar.base.data.ICombination;
import de.featjar.base.data.Result;
import de.featjar.base.data.SingleLexicographicIterator;
import de.featjar.formula.VariableMap;
import de.featjar.formula.assignment.BooleanAssignment;
import de.featjar.formula.assignment.BooleanAssignmentList;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Calculates statistics regarding t-wise feature coverage of a set of
 * solutions.
 *
 * @author Sebastian Krieter
 */
public class ConstraintedCoverageComputation extends ATWiseCoverageComputation {

    public static final Dependency<BooleanAssignmentList> BOOLEAN_CLAUSE_LIST =
            Dependency.newDependency(BooleanAssignmentList.class);
    public static final Dependency<BooleanAssignment> ASSUMED_ASSIGNMENT =
            Dependency.newDependency(BooleanAssignment.class);
    public static final Dependency<BooleanAssignmentList> ASSUMED_CLAUSE_LIST =
            Dependency.newDependency(BooleanAssignmentList.class);
    public static final Dependency<Duration> SAT_TIMEOUT = Dependency.newDependency(Duration.class);
    public static final Dependency<Long> RANDOM_SEED = Dependency.newDependency(Long.class);

    public ConstraintedCoverageComputation(IComputation<BooleanAssignmentList> sample) {
        super(
                sample,
                Computations.of(new BooleanAssignmentList(null, 0)),
                Computations.of(new BooleanAssignment()),
                Computations.of(new BooleanAssignmentList(null, 0)),
                Computations.of(Duration.ZERO),
                Computations.of(1L));
    }

    public ConstraintedCoverageComputation(ConstraintedCoverageComputation other) {
        super(other);
    }

    private BooleanAssignmentList clauseList;
    private BooleanAssignment assumedAssignment;
    private BooleanAssignmentList assumedClauseList;

    private SampleBitIndex randomSampleIndex;
    private Random random;
    private ModalImplicationGraph mig;
    private SAT4JSolutionSolver solver;

    @Override
    protected void initWithOriginalVariableMap(List<Object> dependencyList) {
        super.initWithOriginalVariableMap(dependencyList);
        clauseList = BOOLEAN_CLAUSE_LIST.get(dependencyList);
        random = new Random(RANDOM_SEED.get(dependencyList));
        assumedAssignment = ASSUMED_ASSIGNMENT.get(dependencyList);
        assumedClauseList = ASSUMED_CLAUSE_LIST.get(dependencyList);
    }

    @Override
    protected VariableMap getReferenceVariableMap() {
        return clauseList.getVariableMap();
    }

    @Override
    protected void adaptToMergedVariableMap(VariableMap mergedVariableMap) {
        super.adaptToMergedVariableMap(mergedVariableMap);
        assumedAssignment.adapt(clauseList.getVariableMap(), mergedVariableMap);
        assumedClauseList.adapt(mergedVariableMap);
        clauseList.adapt(mergedVariableMap);
    }

    @Override
    protected void initWithAdaptedVariableMap(List<Object> dependencyList) {
        super.initWithAdaptedVariableMap(dependencyList);
        Duration timeout = SAT_TIMEOUT.get(dependencyList);

        solver = new SAT4JSolutionSolver(clauseList);
        SAT4JSolver.initializeSolver(solver, clauseList, assumedAssignment, assumedClauseList, timeout);
        solver.setSelectionStrategy(ISelectionStrategy.random(random));
        mig = new MIGBuilder(Computations.of(clauseList)).compute();

        randomSampleIndex = new SampleBitIndex(sample.getVariableMap().getVariableCount());
    }

    private boolean isCombinationInvalidMIG(int[] literals) {
        try {
            MIGVisitorByte visitor = new MIGVisitorByte(mig);
            visitor.propagate(literals);
        } catch (RuntimeContradictionException e) {
            return true;
        }
        return false;
    }

    protected Stream<ICombination<CoverageStatistic, int[]>> getCombinationStream(BooleanAssignment variables, int t) {
        return SingleLexicographicIterator.stream(variables.get(), t, this::createStatistic);
    }

    @Override
    protected void countUncovered(int[] uncoveredInteraction, CoverageStatistic statistic) {
        if (randomSampleIndex.test(uncoveredInteraction)) {
            statistic.incNumberOfUncoveredConditions();
        } else if (isCombinationInvalidMIG(uncoveredInteraction)) {
            statistic.incNumberOfInvalidConditions();
        } else {
            int orgAssignmentSize = solver.getAssignment().size();
            solver.getAssignment().addAll(uncoveredInteraction);
            try {
                Result<Boolean> hasSolution = solver.hasSolution();
                if (hasSolution.isPresent()) {
                    if (hasSolution.get()) {
                        int[] solution = solver.getInternalSolution();
                        randomSampleIndex.addConfiguration(solution);
                        solver.shuffleOrder(random);
                        statistic.incNumberOfUncoveredConditions();
                    } else {
                        statistic.incNumberOfInvalidConditions();
                    }
                } else {
                    throw new RuntimeTimeoutException();
                }
            } finally {
                solver.getAssignment().clear(orgAssignmentSize);
            }
        }
    }
}
