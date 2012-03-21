package com.tagtraum.perf.gcviewer.imp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tagtraum.perf.gcviewer.model.AbstractGCEvent;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.Concurrency;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent.GcPattern;
import com.tagtraum.perf.gcviewer.model.ConcurrentGCEvent;
import com.tagtraum.perf.gcviewer.model.GCEvent;
import com.tagtraum.perf.gcviewer.model.GCModel;
import com.tagtraum.perf.gcviewer.util.ParsePosition;

/**
 * <p>Parses log output from Sun / Oracle Java 1.4 / 1.5 / 1.6. / 1.7
 * <br>Supports the following gc algorithms:
 * <ul>
 * <li>-XX:+UseSerialGC</li>
 * <li>-XX:+UseParallelGC</li>
 * <li>-XX:+UseParNewGC</li>
 * <li>-XX:+UseParallelOldGC</li>
 * <li>-XX:+UseConcMarkSweepGC</li>
 * <li>-Xincgc (1.4 / 1.5)</li>
 * </ul>
 * </p>
 * <p>-XX:+UseG1GC is not supported by this class, but by {@link DataReaderSun1_6_0G1}
 * </p>
 * <p>Supports the following options:
 * <ul>
 * <li>-XX:+PrintGCDetails</li>
 * <li>-XX:+PrintGCTimeStamps</li>
 * <li>-XX:+PrintGCDateStamps</li>
 * <li>-XX:+CMSScavengeBeforeRemark</li>
 * <li>-XX:+PrintHeapAtGC (output ignored)</li>
 * <li>-XX:+PrintTenuringDistribution (output ignored)</li>
 * <li>-XX:+PrintAdaptiveSizePolicy (output ignored)</li>
 * </ul>
 * </p>
 * @author <a href="mailto:hs@tagtraum.com">Hendrik Schreiber</a>
 * @author <a href="mailto:gcviewer@gmx.ch">Joerg Wuethrich</a>
 * <p>created on: 23.10.2011 (copied from 1.5 implementation)</p>
 * @see DataReaderSun1_6_0G1
 */
public class DataReaderSun1_6_0 extends AbstractDataReaderSun {

    private static Logger LOG = Logger.getLogger(DataReaderSun1_6_0.class.getName());
    
    private static final String UNLOADING_CLASS = "[Unloading class ";
    private static final String DESIRED_SURVIVOR = "Desired survivor";
    private static final String APPLICATION_TIME = "Application time:";
    private static final String TOTAL_TIME_THREADS_STOPPED = "Total time for which application threads were stopped:";
    private static final String SURVIVOR_AGE = "- age";
    private static final String TIMES_ALONE = " [Times";
    private static final List<String> EXCLUDE_STRINGS = new LinkedList<String>();

    static {
        EXCLUDE_STRINGS.add(UNLOADING_CLASS);
        EXCLUDE_STRINGS.add(DESIRED_SURVIVOR);
        EXCLUDE_STRINGS.add(APPLICATION_TIME);
        EXCLUDE_STRINGS.add(TOTAL_TIME_THREADS_STOPPED);
        EXCLUDE_STRINGS.add(SURVIVOR_AGE);
        EXCLUDE_STRINGS.add(TIMES_ALONE);
    }
    
    private static final String EVENT_YG_OCCUPANCY = "YG occupancy";
    private static final String EVENT_PARNEW = "ParNew";
    private static final String EVENT_DEFNEW = "DefNew";
    
    private static final String CMS_ABORT_PRECLEAN = " CMS: abort preclean due to time ";

    private static final String HEAP_SIZING_START = "Heap";
    
    private static final List<String> HEAP_STRINGS = new LinkedList<String>();
    static {
        HEAP_STRINGS.add("def new generation"); // serial young collection -XX:+UseSerialGC
        HEAP_STRINGS.add("PSYoungGen"); // parallel young collection -XX:+UseParallelGC
        HEAP_STRINGS.add("par new generation"); // parallel young (CMS / -XX:+UseParNewGC)
        HEAP_STRINGS.add("eden space");
        HEAP_STRINGS.add("from space");
        HEAP_STRINGS.add("to   space");
        
        HEAP_STRINGS.add("ParOldGen"); // parallel old collection -XX:+UseParallelOldGC
        HEAP_STRINGS.add("PSOldGen"); // serial old collection -XX:+UseParallelGC without -XX:+UseParallelOldGC
        HEAP_STRINGS.add("object space");
        HEAP_STRINGS.add("PSPermGen"); // serial (?) perm collection
        HEAP_STRINGS.add("tenured generation"); // serial old collection -XX:+UseSerialGC
        HEAP_STRINGS.add("the space");
        HEAP_STRINGS.add("ro space");
        HEAP_STRINGS.add("rw space");
        HEAP_STRINGS.add("compacting perm gen"); // serial perm collection -XX:+UseSerialGC
        HEAP_STRINGS.add("concurrent mark-sweep generation total"); // CMS old collection
        HEAP_STRINGS.add("concurrent-mark-sweep perm gen"); // CMS perm collection
        
        HEAP_STRINGS.add("No shared spaces configured.");
        
        HEAP_STRINGS.add("}");
    }
    
    private static final List<String> ADAPTIVE_SIZE_POLICY_STRINGS = new LinkedList<String>();
    static {
        ADAPTIVE_SIZE_POLICY_STRINGS.add("PSAdaptiveSize");
        ADAPTIVE_SIZE_POLICY_STRINGS.add("AdaptiveSize");
        ADAPTIVE_SIZE_POLICY_STRINGS.add("avg_survived_padded_avg");
    }

    // 1_6_0_u24 mixes lines, when outputing a "promotion failed" which leads to a "concurrent mode failure"
    // pattern looks always like "...[CMS<datestamp>..." or "...[CMS<timestamp>..."
    // the next line starts with " (concurrent mode failure)" which in earlier releases followed "CMS" immediately
    // the same can happen with "...ParNew<timestamp|datestamp>..."
    private static Pattern linesMixedPattern = Pattern.compile("(.* \\[(CMS|ParNew|DefNew))([0-9]+[-.].*)");
    // Matcher group of start of line
    private static final int LINES_MIXED_STARTOFLINE_GROUP = 1;
    // Matcher group of end of line
    private static final int LINES_MIXED_ENDOFLINE_GROUP = 3;
    
    // -XX:+PrintAdaptiveSizePolicy outputs the following lines:
    // 0.175: [GCAdaptiveSizePolicy::compute_survivor_space_size_and_thresh:  survived: 2721008  promoted: 13580768  overflow: trueAdaptiveSizeStart: 0.186 collection: 1 
    // PSAdaptiveSizePolicy::compute_generation_free_space: costs minor_time: 0.059538 major_cost: 0.000000 mutator_cost: 0.940462 throughput_goal: 0.990000 live_space: 273821824 free_space: 33685504 old_promo_size: 16842752 old_eden_size: 16842752 desired_promo_size: 16842752 desired_eden_size: 33685504
    // AdaptiveSizePolicy::survivor space sizes: collection: 1 (2752512, 2752512) -> (2752512, 2752512) 
    // AdaptiveSizeStop: collection: 1 
    //  [PSYoungGen: 16420K->2657K(19136K)] 16420K->15919K(62848K), 0.0109211 secs] [Times: user=0.00 sys=0.00, real=0.01 secs]
    // -> to parse it, the first line must be split, and the following left out until the rest of the gc information follows
    private static Pattern adaptiveSizePolicyPattern = Pattern.compile("(.*GC|.*\\(System\\))Adaptive.*");
    private static final String ADAPTIVE_PATTERN = "AdaptiveSize";

    public DataReaderSun1_6_0(InputStream in) throws UnsupportedEncodingException {
        super(in);
    }

    public GCModel read() throws IOException {
        if (LOG.isLoggable(Level.INFO)) LOG.info("Reading Sun / Oracle 1.4.x / 1.5.x / 1.6.x / 1.7.x format...");
        
        try {
            final GCModel model = new GCModel(false);
            model.setFormat(GCModel.Format.SUN_X_LOG_GC);
            Matcher mixedLineMatcher = linesMixedPattern.matcher("");
            Matcher adaptiveSizePolicyMatcher = adaptiveSizePolicyPattern.matcher("");
            String line;
            // beginningOfLine must be a stack because more than one beginningOfLine might be needed
            Deque<String> beginningOfLine = new LinkedList<String>();
            int lineNumber = 0;
            final ParsePosition parsePosition = new ParsePosition(0);
            OUTERLOOP:
            while ((line = in.readLine()) != null) {
                ++lineNumber;
                parsePosition.setLineNumber(lineNumber);
                if ("".equals(line)) {
                    continue;
                }
                try {
                    // filter out [Unloading class com.xyz] statements
                    for (String i : EXCLUDE_STRINGS) {
                        if (line.indexOf(i) == 0) continue OUTERLOOP;
                    }
                    if (line.indexOf(CMS_ABORT_PRECLEAN) >= 0) {
                        // line contains like " CMS: abort preclean due to time "
                        // -> remove the text
                        int indexOfStart = line.indexOf(CMS_ABORT_PRECLEAN);
                        StringBuilder sb = new StringBuilder(line);
                        sb.replace(indexOfStart, indexOfStart + CMS_ABORT_PRECLEAN.length(), "");
                        line = sb.toString();
                    }

                    if (isCmsScavengeBeforeRemark(line)) {
                        // This is the case, when option -XX:+CMSScavengeBeforeRemark is used.
                        // we have two events in the first line -> split it
                        // if this option is combined with -XX:+PrintTenuringDistribution, the
                        // first event is also distributed over more than one line
                        int startOf2ndEvent = line.indexOf("]", line.indexOf(EVENT_YG_OCCUPANCY)) + 1;
                        beginningOfLine.addFirst(line.substring(0, startOf2ndEvent));
                        if (!isPrintTenuringDistribution(line)) {
                            model.add(parseLine(line.substring(startOf2ndEvent), parsePosition));
                            parsePosition.setIndex(0);
                            continue;
                        }
                        else {
                            beginningOfLine.addFirst(line.substring(startOf2ndEvent));
                            continue;
                        }
                    }
                    final int unloadingClassIndex = line.indexOf(UNLOADING_CLASS);
                    if (unloadingClassIndex > 0) {
                        beginningOfLine.addFirst(line.substring(0, unloadingClassIndex));
                        continue;
                    }
                    else if (isPrintTenuringDistribution(line)) {
                        // this is the case, when e.g. -XX:+PrintTenuringDistribution is used
                        // where we want to skip "Desired survivor..." and "- age..." lines
                        beginningOfLine.addFirst(line);
                        continue;
                    }
                    else if (isMixedLine(line, mixedLineMatcher)) {
                        // if PrintTenuringDistribution is used and a line is mixed, 
                        // beginningOfLine may already contain a value, which must be preserved
                        String firstPartOfBeginningOfLine = beginningOfLine.pollFirst();
                        if (firstPartOfBeginningOfLine == null) {
                            firstPartOfBeginningOfLine = "";
                        }
                        beginningOfLine.addFirst(firstPartOfBeginningOfLine + mixedLineMatcher.group(LINES_MIXED_STARTOFLINE_GROUP));
                        
                        model.add(parseLine(mixedLineMatcher.group(LINES_MIXED_ENDOFLINE_GROUP), parsePosition));
                        parsePosition.setIndex(0);
                        continue;
                    }
                    else if (line.indexOf(ADAPTIVE_PATTERN) >= 0) {
                        adaptiveSizePolicyMatcher.reset(line);
                        if (!adaptiveSizePolicyMatcher.matches()) {
                            LOG.severe("adaptiveSizePolicyMatcher did not match for line " + lineNumber + ": '" + line + "'");
                            continue;
                        }
                        beginningOfLine.addFirst(adaptiveSizePolicyMatcher.group(1));
                        lineNumber = skipLines(in, parsePosition, lineNumber, ADAPTIVE_SIZE_POLICY_STRINGS);
                        continue;
                    }
                    else if (beginningOfLine.size() > 0) {
                        line = beginningOfLine.removeFirst() + line;
                    }
                    else if (line.indexOf(HEAP_SIZING_START) >= 0) {
                        // the next few lines will be the sizing of the heap
                        lineNumber = skipLines(in, parsePosition, lineNumber, HEAP_STRINGS);
                        continue;
                    }
                    model.add(parseLine(line, parsePosition));
                } catch (Exception pe) {
                    if (LOG.isLoggable(Level.WARNING)) LOG.warning(pe.toString());
                    if (LOG.isLoggable(Level.FINE)) LOG.log(Level.FINE, pe.getMessage(), pe);
                    beginningOfLine.clear();
                }
                // reset ParsePosition
                parsePosition.setIndex(0);
            }
            return model;
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException ioe) {
                }
            if (LOG.isLoggable(Level.INFO)) LOG.info("Done reading.");
        }
    }

    private boolean isMixedLine(String line, Matcher mixedLineMatcher) {
        mixedLineMatcher.reset(line);
        return mixedLineMatcher.matches();
    }
    
    private boolean isPrintTenuringDistribution(String line) {
        return line.endsWith("[DefNew") || line.endsWith("[ParNew") || line.endsWith("[ParNew (promotion failed)");
    }

    private boolean isCmsScavengeBeforeRemark(String line) {
        return line.indexOf(EVENT_YG_OCCUPANCY) >= 0 
                && (line.indexOf(EVENT_PARNEW) >= 0 || line.indexOf(EVENT_DEFNEW) >= 0);
    }

    protected AbstractGCEvent<?> parseLine(final String line, final ParsePosition pos) throws ParseException {
        AbstractGCEvent<?> ae = null;
        try {
            // parse datestamp          "yyyy-MM-dd'T'hh:mm:ssZ:"
            // parse timestamp          "double:"
            // parse collection type    "[TYPE"
            // either GC data or another collection type starting with timestamp
            // pre-used->post-used, total, time
            final Date datestamp = parseDatestamp(line, pos);
            final double timestamp = parseTimestamp(line, pos);
            final GCEvent.Type type = parseType(line, pos);
            // special provision for CMS events
            if (type.getConcurrency() == Concurrency.CONCURRENT) {
                if (type.getPattern() == GcPattern.GC) {
                    final ConcurrentGCEvent event = new ConcurrentGCEvent();
                    event.setDateStamp(datestamp);
                    event.setTimestamp(timestamp);
                    event.setType(type);
                    ae = event;
                    // nothing more to parse...
                } else {
                    final ConcurrentGCEvent event = new ConcurrentGCEvent();
                    event.setDateStamp(datestamp);
                    event.setTimestamp(timestamp);
                    event.setType(type);

                    int start = pos.getIndex();
                    int end = line.indexOf('/', pos.getIndex());
                    event.setPause(Double.parseDouble(line.substring(start, end)));
                    start = end + 1;
                    end = line.indexOf(' ', start);
                    event.setDuration(Double.parseDouble(line.substring(start, end)));
                    // nothing more to parse
                    ae = event;
                }
            } else {
                final GCEvent event = new GCEvent();
                event.setDateStamp(datestamp);
                event.setTimestamp(timestamp);
                event.setType(type);
                // now add detail gcevents, should they exist
                int currentIndex = pos.getIndex();
                boolean currentIndexHasChanged = true;
                while (hasNextDetail(line, pos) && currentIndexHasChanged) {
                    final GCEvent detailEvent = new GCEvent();
                    try {
                        if (nextCharIsBracket(line, pos)) {
                            detailEvent.setTimestamp(timestamp);
                        } else {
                            detailEvent.setTimestamp(parseTimestamp(line, pos));
                        }
                        detailEvent.setType(parseType(line, pos));
                        setMemoryAndPauses(detailEvent, line, pos);
                        event.add(detailEvent);
                    } catch (UnknownGcTypeException e) {
                        skipUntilEndOfDetail(line, pos, e);
                    } catch (NumberFormatException e) {
                        skipUntilEndOfDetail(line, pos, e);
                    }
                    
                    // in a line with complete garbage the parser must not get stuck; use emergency exit...
                    currentIndexHasChanged = currentIndex != pos.getIndex();
                    currentIndex = pos.getIndex();
                }
                setMemoryAndPauses(event, line, pos);
                if (event.getPause() == 0) {
                    if (hasNextDetail(line, pos)) {
                        final GCEvent detailEvent = new GCEvent();
                        if (nextCharIsBracket(line, pos)) {
                            detailEvent.setTimestamp(timestamp);
                        } else {
                            detailEvent.setTimestamp(parseTimestamp(line, pos));
                        }
                        detailEvent.setType(parseType(line, pos));
                        setMemoryAndPauses(detailEvent, line, pos);
                        event.add(detailEvent);
                    }
                    parsePause(event, line, pos);
                }
                ae = event;
            }
            return ae;
        } catch (RuntimeException rte) {
            throw new ParseException("Error parsing entry (" + rte.toString() + ")", line, pos);
        }
    }

    private void skipUntilEndOfDetail(final String line, final ParsePosition pos, Exception e) {
        // moving position to the end of this detail event -> skip it
        int indexOfNextBracket = line.indexOf("]", pos.getIndex())+1;
        if (indexOfNextBracket > pos.getIndex()) { 
            pos.setIndex(indexOfNextBracket);
            while (line.charAt(pos.getIndex()) == ' ') {
                pos.setIndex(pos.getIndex()+1);
            }
        }
        if (LOG.isLoggable(Level.FINE)) LOG.fine("Skipping detail event because of " + e);
    }
    
}
