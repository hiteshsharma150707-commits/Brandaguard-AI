import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BrandGuard AI - backend (beginner friendly).
 * Uses only built-in Java classes. No Spring Boot, no external libraries.
 * The content checker is RULE-BASED (regular expressions). It does not call any AI API.
 *
 * Run:   javac Main.java   then   java Main      (open http://localhost:8080)
 * Keep index.html and style.css in the same folder as Main.java.
 */
public class Main {

    // ---------- Simple data classes ----------
    static class Issue {
        String type, severity, matched, message, approved, suggestion;
        int start, end;
    }

    static class Submission {
        int id;
        String text, status;
        List<Issue> issues;
    }

    // ---------- In-memory "database" (resets when the server restarts) ----------
    static final List<Submission> queue = new ArrayList<>();
    static final Map<String, Integer> byType = new LinkedHashMap<>();
    static int totalChecks = 0, issuesDetected = 0, nextId = 1;

    static final String FACT_WARRANTY = "Our product comes with a 2-year warranty.";

    // ---------- Rule tables: regex -> replacement ----------
    static final Map<String, String> CLAIMS = new LinkedHashMap<>();
    static final Map<String, String> TONE = new LinkedHashMap<>();

    static {
        CLAIMS.put("(?i)\\bbest\\b", "high-quality");
        CLAIMS.put("(?i)(#1|\\bnumber one\\b)", "trusted");
        CLAIMS.put("(?i)\\bguaranteed?\\b", "designed");
        CLAIMS.put("(?i)100%", "high");
        CLAIMS.put("(?i)\\bperfect\\b", "reliable");
        CLAIMS.put("(?i)\\bunbeatable\\b", "competitive");
        CLAIMS.put("(?i)\\bcheapest\\b", "affordable");
        CLAIMS.put("(?i)\\brisk-free\\b", "easy to try");
        TONE.put("(?i)\\bgonna\\b", "going to");
        TONE.put("(?i)\\bwanna\\b", "want to");
        TONE.put("(?i)\\bguys\\b", "everyone");
        TONE.put("(?i)\\bstupid\\b", "unwise");
        TONE.put("!{2,}", "!");
        for (String t : new String[]{"Factual Inconsistency", "Unsupported Claim", "Terminology", "Tone"}) byType.put(t, 0);
        // Seed a few demo items so the dashboard is not empty
        seed("Hello customers! Our product comes with a 2-year warranty.", "Approved");
        seed("We are the best and gonna ship it fast, guaranteed!!", "Changes Requested");
        seed("Our product comes with a 5-year warranty.", "Pending Review");
    }

    static void seed(String text, String status) {
        List<Issue> found = analyse(text);
        recordCheck(found);
        addSubmission(text, found, status);
    }

    // ---------- The rule-based checker ----------
    static List<Issue> analyse(String text) {
        List<Issue> all = new ArrayList<>();

        // Rule 1: warranty must be 2 years. Pattern A: "5-year warranty"
        Matcher a = Pattern.compile("(?i)(\\d+)[\\s-]*(?:years?|yrs?)(?=[^.!?]*warrant)").matcher(text);
        while (a.find()) {
            if (!a.group(1).equals("2")) all.add(warrantyIssue(a.start(), a.end(), a.group(), "2-year"));
        }
        // Pattern B: "warranty for 5 years"
        Matcher b = Pattern.compile("(?i)warrant\\w*[^.!?\\d]*?(\\d+)[\\s-]*(?:years?|yrs?)").matcher(text);
        while (b.find()) {
            if (!b.group(1).equals("2")) all.add(warrantyIssue(b.start(1), b.end(), b.group().substring(b.start(1) - b.start()), "2 years"));
        }
        // Pattern C: "lifetime warranty"
        Matcher c = Pattern.compile("(?i)\\blifetime(?=\\s+warrant)").matcher(text);
        while (c.find()) all.add(warrantyIssue(c.start(), c.end(), c.group(), "2-year"));

        // Rule 2: unsupported promotional claims
        for (Map.Entry<String, String> e : CLAIMS.entrySet()) {
            Matcher m = Pattern.compile(e.getKey()).matcher(text);
            while (m.find()) all.add(make("Unsupported Claim", "Medium", m.start(), m.end(), m.group(),
                    "\"" + m.group() + "\" is a promotional claim with no approved evidence behind it.",
                    "No unsupported promotional claims.", e.getValue()));
        }

        // Rule 3: approved terminology
        Matcher cl = Pattern.compile("(?i)\\bclients?\\b").matcher(text);
        while (cl.find()) {
            String s = cl.group().toLowerCase().endsWith("s") ? "customers" : "customer";
            all.add(make("Terminology", "Low", cl.start(), cl.end(), cl.group(),
                    "The approved term is \"customer\", not \"client\".", "Approved term: customer", s));
        }
        Matcher bg = Pattern.compile("(?i)\\bbrand[\\s-]?guard(?:\\s?ai)?\\b").matcher(text);
        while (bg.find()) {
            if (!bg.group().equals("BrandGuard AI"))
                all.add(make("Terminology", "Low", bg.start(), bg.end(), bg.group(),
                        "The product name must be written exactly as \"BrandGuard AI\".", "Approved name: BrandGuard AI", "BrandGuard AI"));
        }

        // Rule 4: professional and friendly tone
        for (Map.Entry<String, String> e : TONE.entrySet()) {
            Matcher m = Pattern.compile(e.getKey()).matcher(text);
            while (m.find()) all.add(make("Tone", "Low", m.start(), m.end(), m.group(),
                    "\"" + m.group() + "\" sounds too casual. Brand tone should be professional and friendly.",
                    "Tone: professional and friendly.", e.getValue()));
        }

        // Sort by position and drop overlapping findings
        all.sort(Comparator.comparingInt(i -> i.start));
        List<Issue> result = new ArrayList<>();
        int lastEnd = -1;
        for (Issue i : all) {
            if (i.start >= lastEnd) { result.add(i); lastEnd = i.end; }
        }
        return result;
    }

    static Issue warrantyIssue(int s, int e, String matched, String replacement) {
        return make("Factual Inconsistency", "High", s, e, matched,
                "The warranty period in the content (\"" + matched + "\") does not match the approved company fact.",
                FACT_WARRANTY, replacement);
    }

    static Issue make(String type, String sev, int s, int e, String matched, String msg, String approved, String sugg) {
        Issue i = new Issue();
        i.type = type; i.severity = sev; i.start = s; i.end = e;
        i.matched = matched; i.message = msg; i.approved = approved; i.suggestion = sugg;
        return i;
    }

    // ---------- Data helpers (synchronized: the server handles requests on several threads) ----------
    static synchronized void recordCheck(List<Issue> found) {
        totalChecks++;
        issuesDetected += found.size();
        for (Issue i : found) byType.merge(i.type, 1, Integer::sum);
    }

    static synchronized int addSubmission(String text, List<Issue> found, String status) {
        Submission s = new Submission();
        s.id = nextId++; s.text = text; s.issues = found; s.status = status;
        queue.add(0, s); // newest first
        return s.id;
    }

    static synchronized boolean review(int id, String status) {
        for (Submission s : queue) if (s.id == id) { s.status = status; return true; }
        return false;
    }

    // ---------- JSON helpers (written by hand, no libraries) ----------
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String issueJson(Issue i) {
        return "{\"type\":\"" + esc(i.type) + "\",\"severity\":\"" + i.severity + "\",\"start\":" + i.start + ",\"end\":" + i.end
                + ",\"matched\":\"" + esc(i.matched) + "\",\"message\":\"" + esc(i.message)
                + "\",\"approved\":\"" + esc(i.approved) + "\",\"suggestion\":\"" + esc(i.suggestion) + "\"}";
    }

    static String issuesJson(List<Issue> list) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Issue i : list) j.add(issueJson(i));
        return j.toString();
    }

    /** Reads a string value such as {"text":"hello"} from a JSON body. */
    static String getString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int q = json.indexOf('"', json.indexOf(':', k) + 1);
        if (q < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int p = q + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '"') break;
            if (ch == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else if (n == 'r') sb.append('\r');
                else if (n == 'u' && p + 4 < json.length()) { sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16)); p += 4; }
                else sb.append(n);
            } else sb.append(ch);
        }
        return sb.toString();
    }

    // ---------- HTTP handling ----------
    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    static void json(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html", Files.readAllBytes(Paths.get("index.html")));
            } else if (path.equals("/style.css")) {
                send(ex, 200, "text/css", Files.readAllBytes(Paths.get("style.css")));

            } else if (path.equals("/api/analyse") && method.equals("POST")) {
                String text = getString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), "text");
                List<Issue> found = analyse(text);
                recordCheck(found);
                json(ex, "{\"issues\":" + issuesJson(found) + "}");

            } else if (path.equals("/api/submit") && method.equals("POST")) {
                String text = getString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), "text");
                int id = addSubmission(text, analyse(text), "Pending Review"); // re-checked on the server
                json(ex, "{\"id\":" + id + "}");

            } else if (path.equals("/api/review") && method.equals("POST")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = review(Integer.parseInt(getString(body, "id")), getString(body, "status"));
                json(ex, "{\"ok\":" + ok + "}");

            } else if (path.equals("/api/queue")) {
                StringJoiner j = new StringJoiner(",", "[", "]");
                synchronized (Main.class) {
                    for (Submission s : queue)
                        j.add("{\"id\":" + s.id + ",\"text\":\"" + esc(s.text) + "\",\"status\":\"" + s.status
                                + "\",\"issues\":" + issuesJson(s.issues) + "}");
                }
                json(ex, j.toString());

            } else if (path.equals("/api/stats")) {
                int approved = 0, pending = 0;
                StringJoiner t = new StringJoiner(",", "{", "}");
                synchronized (Main.class) {
                    for (Submission s : queue) {
                        if (s.status.equals("Approved")) approved++;
                        if (s.status.equals("Pending Review")) pending++;
                    }
                    for (Map.Entry<String, Integer> e : byType.entrySet()) t.add("\"" + e.getKey() + "\":" + e.getValue());
                    json(ex, "{\"total\":" + totalChecks + ",\"issues\":" + issuesDetected + ",\"approved\":" + approved
                            + ",\"pending\":" + pending + ",\"byType\":" + t + "}");
                }

            } else if (path.equals("/api/guidelines")) {
                json(ex, "[{\"icon\":\"💬\",\"title\":\"Professional and friendly tone\",\"text\":\"Write clearly and politely. Avoid slang such as gonna, wanna or guys, and avoid repeated exclamation marks.\"},"
                        + "{\"icon\":\"📖\",\"title\":\"Approved terminology\",\"text\":\"Say customer (not client). Write the product name exactly as BrandGuard AI.\"},"
                        + "{\"icon\":\"🛡️\",\"title\":\"Product warranty: 2 years\",\"text\":\"" + esc(FACT_WARRANTY) + "\"},"
                        + "{\"icon\":\"🚫\",\"title\":\"No unsupported promotional claims\",\"text\":\"Avoid words like best, #1, guaranteed, perfect, 100% or cheapest unless evidence is approved.\"}]");
            } else {
                send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            send(ex, 500, "text/plain", ("Server error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Main::handle);
        server.start();
        System.out.println("BrandGuard AI running at http://localhost:8080");
    }
}
