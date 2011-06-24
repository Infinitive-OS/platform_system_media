/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.filterfw.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;

import android.filterfw.core.FilterContext;
import android.filterfw.core.KeyValueMap;
import android.filterpacks.base.FrameBranch;
import android.filterpacks.base.NullFilter;

import android.util.Log;

public class FilterGraph {

    private HashSet<Filter> mFilters = new HashSet<Filter>();
    private HashMap<String, Filter> mNameMap = new HashMap<String, Filter>();
    private HashMap<SourcePort, LinkedList<TargetPort>> mPreconnections = new
            HashMap<SourcePort, LinkedList<TargetPort>>();

    public static final int AUTOBRANCH_OFF      = 0;
    public static final int AUTOBRANCH_SYNCED   = 1;
    public static final int AUTOBRANCH_UNSYNCED = 2;

    private boolean mIsReady = false;
    private int mAutoBranchMode = AUTOBRANCH_OFF;
    private boolean mDiscardUnconnectedOutputs = false;

    public FilterGraph() {
    }

    public boolean addFilter(Filter filter) {
        if (!containsFilter(filter)) {
            mFilters.add(filter);
            mNameMap.put(filter.getName(), filter);
            return true;
        }
        return false;
    }

    public boolean containsFilter(Filter filter) {
        return mFilters.contains(filter);
    }

    public Filter getFilter(String name) {
        return mNameMap.get(name);
    }

    public void connect(Filter source,
                        String outputName,
                        Filter target,
                        String inputName) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("Passing null Filter in connect()!");
        } else if (!containsFilter(source) || !containsFilter(target)) {
            throw new RuntimeException("Attempting to connect filter not in graph!");
        }

        SourcePort outPort = source.getOutputPort(outputName);
        TargetPort inPort = target.getInputPort(inputName);
        if (outPort == null) {
            throw new RuntimeException("Unknown output port '" + outputName + "' on Filter " +
                                       source + "!");
        } else if (inPort == null) {
            throw new RuntimeException("Unknown input port '" + inputName + "' on Filter " +
                                       target + "!");
        }

        preconnect(outPort, inPort);
    }

    public void connect(String sourceName,
                        String outputName,
                        String targetName,
                        String inputName) {
        Filter source = getFilter(sourceName);
        Filter target = getFilter(targetName);
        if (source == null) {
            throw new RuntimeException(
                "Attempting to connect unknown source filter '" + sourceName + "'!");
        } else if (target == null) {
            throw new RuntimeException(
                "Attempting to connect unknown target filter '" + targetName + "'!");
        }
        connect(source, outputName, target, inputName);
    }

    public Set<Filter> getFilters() {
        return mFilters;
    }

    public void beginProcessing() {
        Log.v("FilterGraph", "Opening all filter connections...");
        for (Filter filter : mFilters) {
            filter.openOutputs();
        }
        mIsReady = true;
    }

    public void closeFilters(FilterContext context) {
        Log.v("FilterGraph", "Closing all filters...");
        for (Filter filter : mFilters) {
            filter.performClose(context);
        }
        mIsReady = false;
    }

    public boolean isReady() {
        return mIsReady;
    }

    public void setAutoBranchMode(int autoBranchMode) {
        mAutoBranchMode = autoBranchMode;
    }

    public void setDiscardUnconnectedOutputs(boolean discard) {
        mDiscardUnconnectedOutputs = discard;
    }

    private boolean readyForProcessing(Filter filter, Set<Filter> processed) {
        // Check if this has been already processed
        if (processed.contains(filter)) {
            return false;
        }

        // Check if all dependencies have been processed
        for (TargetPort port : filter.getInputPorts()) {
            Filter dependency = port.getSourceFilter();
            if (dependency != null && !processed.contains(dependency)) {
                return false;
            }
        }
        return true;
    }

    private void runTypeCheck(boolean strict) {
        Stack<Filter> filterStack = new Stack<Filter>();
        Set<Filter> processedFilters = new HashSet<Filter>();
        filterStack.addAll(getSourceFilters());

        while (!filterStack.empty()) {
            // Get current filter and mark as processed
            Filter filter = filterStack.pop();
            processedFilters.add(filter);

            // Anchor output formats
            updateOutputs(filter);

            // Perform type check
            Log.v("FilterGraph", "Running type check on " + filter + "...");
            runTypeCheckOn(filter, strict);

            // Push connected filters onto stack
            for (SourcePort port : filter.getOutputPorts()) {
                Filter target = port.getTargetFilter();
                if (target != null && readyForProcessing(target, processedFilters)) {
                    filterStack.push(target);
                }
            }
        }

        // Make sure all ports were setup
        if (processedFilters.size() != getFilters().size()) {
            throw new RuntimeException("Could not schedule all filters! Is your graph malformed?");
        }
    }

    private void updateOutputs(Filter filter) {
        for (SourcePort outputPort : filter.getOutputPorts()) {
            TargetPort inputPort = outputPort.getBasePort();
            if (inputPort != null) {
                FrameFormat inputFormat = inputPort.getSourceFormat();
                FrameFormat outputFormat = filter.getOutputFormat(outputPort.getName(),
                                                                  inputFormat);
                if (outputFormat == null) {
                    throw new RuntimeException("Filter did not return an output format for "
                        + outputPort + "!");
                }
                outputPort.setPortFormat(outputFormat);
            }
        }
    }

    private void runTypeCheckOn(Filter filter, boolean strict) {
        for (TargetPort inputPort : filter.getInputPorts()) {
            Log.v("FilterGraph", "Type checking port " + inputPort);
            FrameFormat sourceFormat = inputPort.getSourceFormat();
            FrameFormat targetFormat = inputPort.getPortFormat();
            if (sourceFormat != null && targetFormat != null) {
                Log.v("FilterGraph", "Checking " + sourceFormat + " against " + targetFormat + ".");
                boolean compatible = strict ? sourceFormat.isCompatibleWith(targetFormat)
                                            : sourceFormat.mayBeCompatibleWith(targetFormat);
                if (!compatible) {
                    throw new RuntimeException("Type mismatch: Filter " + filter + " expects a "
                        + "format of type " + targetFormat + " but got a format of type "
                        + sourceFormat + "!");
                }
            }
        }
    }

    private void checkConnections() {
        // TODO
    }

    private void discardUnconnectedOutputs() {
        // Connect unconnected ports to Null filters
        LinkedList<Filter> addedFilters = new LinkedList<Filter>();
        for (Filter filter : mFilters) {
            int id = 0;
            for (SourcePort port : filter.getOutputPorts()) {
                if (!port.isConnected()) {
                    Log.v("FilterGraph", "Autoconnecting unconnected " + port + " to Null filter.");
                    NullFilter nullFilter = new NullFilter(filter.getName() + "ToNull" + id);
                    nullFilter.init();
                    addedFilters.add(nullFilter);
                    port.connectTo(nullFilter.getInputPort("frame"));
                    ++id;
                }
            }
        }
        // Add all added filters to this graph
        for (Filter filter : addedFilters) {
            addFilter(filter);
        }
    }

    private void removeFilter(Filter filter) {
        mFilters.remove(filter);
        mNameMap.remove(filter.getName());
    }

    private void preconnect(SourcePort outPort, TargetPort inPort) {
        LinkedList<TargetPort> targets;
        targets = mPreconnections.get(outPort);
        if (targets == null) {
            targets = new LinkedList<TargetPort>();
            mPreconnections.put(outPort, targets);
        }
        targets.add(inPort);
    }

    private void connectPorts() {
        int branchId = 1;
        for (Entry<SourcePort, LinkedList<TargetPort>> connection : mPreconnections.entrySet()) {
            SourcePort sourcePort = connection.getKey();
            LinkedList<TargetPort> targetPorts = connection.getValue();
            if (targetPorts.size() == 1) {
                sourcePort.connectTo(targetPorts.get(0));
            } else if (mAutoBranchMode == AUTOBRANCH_OFF) {
                throw new RuntimeException("Attempting to connect " + sourcePort + " to multiple "
                                         + "filter ports! Enable auto-branching to allow this.");
            } else {
                Log.v("FilterGraph", "Creating branch for " + sourcePort + "!");
                FrameBranch branch = null;
                if (mAutoBranchMode == AUTOBRANCH_SYNCED) {
                    branch = new FrameBranch("branch" + branchId++);
                } else {
                    throw new RuntimeException("TODO: Unsynced branches not implemented yet!");
                }
                KeyValueMap branchParams = new KeyValueMap();
                branch.initWithAssignmentList("outputs", targetPorts.size());
                addFilter(branch);
                sourcePort.connectTo(branch.getInputPort("in"));
                Iterator<TargetPort> targetPortIter = targetPorts.iterator();
                for (SourcePort branchOutPort : ((Filter)branch).getOutputPorts()) {
                    branchOutPort.connectTo(targetPortIter.next());
                }
            }
        }
        mPreconnections.clear();
    }

    private HashSet<Filter> getSourceFilters() {
        HashSet<Filter> sourceFilters = new HashSet<Filter>();
        for (Filter filter : getFilters()) {
            if (filter.getNumberOfConnectedInputs() == 0) {
                Log.v("FilterGraph", "Found source filter: " + filter);
                sourceFilters.add(filter);
            }
        }
        return sourceFilters;
    }

    // Core internal methods /////////////////////////////////////////////////////////////////////////
    void setupFilters() {
        if (mDiscardUnconnectedOutputs) {
            discardUnconnectedOutputs();
        }
        connectPorts();
        checkConnections();
        runTypeCheck(true); // TODO: allow non-strict type-checking
    }

    void tearDownFilters(FilterContext context) {
        for (Filter filter : mFilters) {
            filter.tearDown(context);
        }
    }
}
