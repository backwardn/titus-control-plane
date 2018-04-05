/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.common.runtime.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.spectator.api.BasicTag;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Tag;
import com.netflix.titus.common.framework.fit.FitFramework;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.util.ReflectionExt;
import com.netflix.titus.common.util.code.CodeInvariants;
import com.netflix.titus.common.util.code.CodePointTracker;
import com.netflix.titus.common.util.code.SpectatorCodePointTracker;
import com.netflix.titus.common.util.rx.RetryHandlerBuilder;
import com.netflix.titus.common.util.spectator.SpectatorExt;
import com.netflix.titus.common.util.time.Clock;
import com.netflix.titus.common.util.time.Clocks;
import rx.Observable;

@Singleton
public class DefaultTitusRuntime implements TitusRuntime {

    public static final String FIT_ACTIVATION_PROPERTY = "titus.runtime.fit.enabled";

    private static final String METRICS_RUNTIME_ROOT = "titus.system.";
    private static final String METRICS_PERSISTENT_STREAM = METRICS_RUNTIME_ROOT + "persistentStream";

    private static final String UNKNOWN = "UNKNOWN";

    private static final long INITIAL_RETRY_DELAY_MS = 10;
    private static final long MAX_RETRY_DELAY_MS = 10_000;

    private final CodePointTracker codePointTracker;
    private final CodeInvariants codeInvariants;
    private final Registry registry;
    private final Clock clock;
    private final FitFramework fitFramework;

    @Inject
    public DefaultTitusRuntime(CodeInvariants codeInvariants, Registry registry) {
        this(
                new SpectatorCodePointTracker(registry),
                codeInvariants,
                registry,
                Clocks.system(),
                "true".equals(System.getProperty(FIT_ACTIVATION_PROPERTY, "false"))
        );
    }

    public DefaultTitusRuntime(CodePointTracker codePointTracker, CodeInvariants codeInvariants, Registry registry, Clock clock, boolean isFitEnabled) {
        this.codePointTracker = codePointTracker;
        this.codeInvariants = codeInvariants;
        this.registry = registry;
        this.clock = clock;
        this.fitFramework = isFitEnabled ? FitFramework.newFitFramework() : FitFramework.inactiveFitFramework();
    }

    @Override
    public <T> Observable<T> persistentStream(Observable<T> source) {
        String callerName;
        List<Tag> commonTags;
        Optional<StackTraceElement> callerStackTraceOpt = ReflectionExt.findCallerStackTrace();
        if (callerStackTraceOpt.isPresent()) {
            StackTraceElement callerStackTrace = callerStackTraceOpt.get();
            commonTags = Arrays.asList(
                    new BasicTag("class", callerStackTrace.getClassName()),
                    new BasicTag("method", callerStackTrace.getMethodName()),
                    new BasicTag("line", Integer.toString(callerStackTrace.getLineNumber()))
            );
            callerName = callerStackTrace.getClassName() + '.' + callerStackTrace.getMethodName() + '@' + callerStackTrace.getLineNumber();
        } else {
            callerName = UNKNOWN;
            commonTags = Arrays.asList(
                    new BasicTag("class", UNKNOWN),
                    new BasicTag("method", UNKNOWN),
                    new BasicTag("line", UNKNOWN)
            );
        }

        return source
                .compose(SpectatorExt.subscriptionMetrics(METRICS_PERSISTENT_STREAM, commonTags, registry))
                .retryWhen(RetryHandlerBuilder.retryHandler()
                        .withUnlimitedRetries()
                        .withDelay(INITIAL_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        .withTitle("Auto-retry for " + callerName)
                        .buildExponentialBackoff()
                );
    }

    @Override
    public Clock getClock() {
        return clock;
    }

    @Override
    public CodePointTracker getCodePointTracker() {
        return codePointTracker;
    }

    @Override
    public CodeInvariants getCodeInvariants() {
        return codeInvariants;
    }

    @Override
    public Registry getRegistry() {
        return registry;
    }

    @Override
    public FitFramework getFitFramework() {
        return fitFramework;
    }
}