/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package cn.orz.pascal.jfr.viewer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import static java.util.AbstractMap.*;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import static cn.orz.pascal.jl2.collections.Tuples.*;
import static cn.orz.pascal.jl2.Extentions.*;
import java.nio.file.Files;

/**
 *
 * @author koduki
 */
public class JFRParser {

    public static void main(String[] args) throws IOException {
        var methods = readMethod(Paths.get("test.jfr"));
        report(parseSpan(methods._1(), methods._2()), Paths.get("target/test.html"));
    }

    static Tuple2<LinkedHashMap<String, Set<Instant>>, List<Instant>> readMethod(Path path) throws IOException {
        var times = new ArrayList<Instant>();
        var methods = new LinkedHashMap<String, Set<Instant>>();
        RecordingFile.readAllEvents(path).stream()
                .filter((e) -> e.getEventType().getName().endsWith(".ExecutionSample"))
                .forEach((event) -> {
                    RecordedStackTrace stacktrace = event.getStackTrace();
                    if (stacktrace != null) {
                        times.add(event.getStartTime());
                        addMthods(methods, stacktrace, event);
                    }
                });

        return $(methods, times);
    }

    static void addMthods(LinkedHashMap<String, Set<Instant>> methods, RecordedStackTrace stacktrace, RecordedEvent event) {
        stacktrace.getFrames().stream()
                .filter(x -> x.isJavaFrame())
                .filter(x -> !x.getMethod().getType().getName().startsWith("jdk.jfr."))
                .collect(Collectors.toCollection(ArrayDeque::new))
                .descendingIterator()
                .forEachRemaining(x -> {
                    RecordedMethod method = x.getMethod();
                    String key = method.getType().getName() + "#" + method.getName();
                    Set<Instant> span = methods.getOrDefault(key, new HashSet<>());
                    span.add(event.getStartTime());
                    methods.put(key, span);
                });
    }

    static void report(List<Tuple2<String, List<Tuple2<Instant, Instant>>>> spans, Path path) throws IOException {
        var names = spans.stream().map(xs -> xs._1()).distinct().collect(Collectors.toList());

        var htmlNames = names.stream().map(x -> String.format("\"%s\"", x)).collect(Collectors.toList());
        var htmlItems = new ArrayList<String>();
        int index = 0;
        for (int i = 0; i < spans.size(); i++) {
            var s = spans.get(i);
            String msg = "{ id: %d, group: %d, content: \"\", start: \"%s\", end: \"%s\" }";
            for (int j = 0; j < s._2().size(); j++) {
                var x = s._2().get(j);
                htmlItems.add(String.format(msg, index++, names.indexOf(s._1()), x._1(), x._2()));
            }
        }

        var html = "<!DOCTYPE html>\n"
                + "<html>\n"
                + "  <head>\n"
                + "    <title>Timeline</title>\n"
                + "\n"
                + "    <style type=\"text/css\">\n"
                + "      body,\n"
                + "      html {\n"
                + "        font-family: sans-serif;\n"
                + "      }\n"
                + "    </style>\n"
                + "\n"
                + "    <script src=\"http://visjs.org/dist/vis.js\"></script>\n"
                + "    <link\n"
                + "      href=\"http://visjs.org/dist/vis-timeline-graph2d.min.css\"\n"
                + "      rel=\"stylesheet\"\n"
                + "      type=\"text/css\"\n"
                + "    />\n"
                + "  </head>\n"
                + "  <body>\n"
                + "    <p>\n"
                + "      A Simple Timeline\n"
                + "    </p>\n"
                + "\n"
                + "    <div id=\"visualization\"></div>\n"
                + "\n"
                + "    <script type=\"text/javascript\">\n"
                + "            // DOM element where the Timeline will be attached\n"
                + "            var container = document.getElementById(\"visualization\");\n"
                + "\n"
                + "            // create a data set with groups\n"
                + "            var names = [" + String.join(",", htmlNames) + "];\n"
                + "            var groups = new vis.DataSet();\n"
                + "            for (var g = 0; g < names.length; g++) {\n"
                + "              groups.add({ id: g, content: names[g] });\n"
                + "            }\n"
                + "\n"
                + "   \n"
                + "            // Create a DataSet (allows two way data-binding)\n"
                + "            var items = new vis.DataSet([" + String.join(",", htmlItems) + "]);\n"
                + "\n"
                + "            // Configuration for the Timeline\n"
                + "            function customOrder(a, b) {\n"
                + "              // order by id\n"
                + "              return a.id - b.id;\n"
                + "            }\n"
                + "\n"
                + "            // Configuration for the Timeline\n"
                + "            var options = {\n"
                + "              order: customOrder,\n"
                + "              editable: true,\n"
                + "              margin: { item: 0 }\n"
                + "            };\n"
                + "\n"
                + "            // Create a Timeline\n"
                + "            var timeline = new vis.Timeline(container);\n"
                + "            timeline.setOptions(options);\n"
                + "            timeline.setGroups(groups);\n"
                + "            timeline.setItems(items);\n"
                + "    </script>\n"
                + "  </body>\n"
                + "</html>";

        Files.writeString(path, html);

    }

    static List<Tuple2<String, List<Tuple2<Instant, Instant>>>> parseSpan(LinkedHashMap<String, Set<Instant>> methods, List<Instant> times) {
        var span = new ArrayList<Tuple2<String, List<Tuple2<Instant, Instant>>>>();
        for (Entry<String, Set<Instant>> m : methods.entrySet()) {
            var key = m.getKey();
            var value = m.getValue();

            var xs = new ArrayList<Tuple2<Instant, Instant>>();
            Instant start = null;
            Instant last = null;
            for (Instant t : times) {
                if (value.contains(t)) {
                    if (start == null) {
                        start = t;
                    }
                    last = t;
                } else {
                    if (last != null) {
                        xs.add($(start, last));
                    }
                    start = null;
                    last = null;
                }
            }
            span.add($(key, xs));
        }
        return span;
    }
}
