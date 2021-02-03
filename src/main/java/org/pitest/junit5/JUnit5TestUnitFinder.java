/*
 * Copyright 2017 Tobias Stadler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package org.pitest.junit5;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.pitest.testapi.TestGroupConfig;
import org.pitest.testapi.TestUnit;
import org.pitest.testapi.TestUnitFinder;

/**
 *
 * @author Tobias Stadler
 */
public class JUnit5TestUnitFinder implements TestUnitFinder {

    private final TestGroupConfig testGroupConfig;

    private final Collection<String> includedTestMethods;

    private final Launcher launcher;

    public JUnit5TestUnitFinder(TestGroupConfig testGroupConfig, Collection<String> includedTestMethods) {
        this.testGroupConfig = testGroupConfig;
        this.includedTestMethods = includedTestMethods;
        this.launcher = LauncherFactory.create();
    }

    @Override
    public List<TestUnit> findTestUnits(Class<?> clazz) {
        System.err.println("findTestUnits for " + clazz);
        if(clazz.getEnclosingClass() != null) {
            return emptyList();
        }

        List<TestIdentifier> collectedIdentifiers = new ArrayList<>();
        CollectIdentifierFilters identifierFilter = new CollectIdentifierFilters(collectedIdentifiers);
        TestIdentifierListener listener = new TestIdentifierListener(collectedIdentifiers);
        launcher.execute(LauncherDiscoveryRequestBuilder
                .request()
                .selectors(DiscoverySelectors.selectClass(clazz))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled","true")
                .filters(identifierFilter)
                .build(), listener);
        System.err.println("End findTestUnits for " + clazz);
        return collectedIdentifiers
                .stream()
                .map(testIdentifier -> new JUnit5TestUnit(clazz, testIdentifier))
                .collect(toList());
    }

    private boolean isTestMethodIncluded(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            // filter out testMethods
            if (includedTestMethods != null && !includedTestMethods.isEmpty()
                    && testIdentifier.getSource().isPresent()
                    && testIdentifier.getSource().get() instanceof MethodSource
                    && !includedTestMethods.contains(((MethodSource)testIdentifier.getSource().get()).getMethodName())) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * This listener collects the identifiers of executed tests.
     */
    private class TestIdentifierListener extends SummaryGeneratingListener {
		private final List<TestIdentifier> identifiers;

        public TestIdentifierListener(List<TestIdentifier> collectInto) {
            identifiers = collectInto;
        }

		@Override
		public void executionStarted(TestIdentifier testIdentifier) {
		    if (isTestMethodIncluded(testIdentifier)) {
                identifiers.add(testIdentifier);
			}
		}
    }

    private class CollectIdentifierFilters implements PostDiscoveryFilter {

        private final Filter<TestDescriptor> combinedTagFilter;
        private final List<TestIdentifier> identifiers;

        CollectIdentifierFilters(List<TestIdentifier> collectTo) {
            identifiers = collectTo;
            List<PostDiscoveryFilter> tagFilters = new ArrayList<>(2);
            try {
                List<String> excludedGroups = testGroupConfig.getExcludedGroups();
                if(excludedGroups != null && !excludedGroups.isEmpty()) {
                    tagFilters.add(TagFilter.excludeTags(excludedGroups));
                }

                List<String> includedGroups = testGroupConfig.getIncludedGroups();
                if(includedGroups != null && !includedGroups.isEmpty()) {
                    tagFilters.add(TagFilter.includeTags(includedGroups));
                }
            } catch(PreconditionViolationException e) {
                throw new IllegalArgumentException("Error creating tag filter", e);
            }
            combinedTagFilter = Filter.composeFilters(tagFilters);
        }

        public FilterResult _apply(TestDescriptor testDescriptor) {
            FilterResult tagResult = combinedTagFilter.apply(testDescriptor);
            if (tagResult.excluded()) {
                return tagResult;
            }

            if (testDescriptor.mayRegisterTests()) {
                return FilterResult.included(null);
            } else {
                TestIdentifier identifier = TestIdentifier.from(testDescriptor);
                if (isTestMethodIncluded(identifier)) {
                    identifiers.add(identifier);
                }
                return FilterResult.excluded(null);
            }
        }

        @Override
        public FilterResult apply(TestDescriptor testDescriptor) {
            FilterResult tagResult = combinedTagFilter.apply(testDescriptor);
                return tagResult;
        }

    }
}
