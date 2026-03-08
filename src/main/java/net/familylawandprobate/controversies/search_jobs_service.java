package net.familylawandprobate.controversies;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tenant-aware asynchronous search jobs with pluggable search types.
 */
public final class search_jobs_service {

    private static final Logger LOG = Logger.getLogger(search_jobs_service.class.getName());
    private static final int SEARCH_WORKERS = 2;
    private static final int MAX_RESULTS_PER_JOB = 500;
    private static final int DEFAULT_RESULTS_PER_JOB = 200;
    private static final int MAX_JOBS_RETAINED = 400;
    private static final long JOB_RETENTION_MS = 24L * 60L * 60L * 1000L;

    private static final class Holder {
        private static final search_jobs_service INSTANCE = new search_jobs_service();
    }

    public static final class SearchJobRequest {
        public String tenantUuid;
        public String requestedBy;
        public String searchType;
        public String query;
        public String operator;
        public String logic = "or";
        public ArrayList<SearchCriterion> criteria = new ArrayList<SearchCriterion>();
        public boolean caseSensitive;
        public boolean includeMetadata = true;
        public boolean includeOcr = true;
        public int maxResults = DEFAULT_RESULTS_PER_JOB;
    }

    public static final class SearchCriterion {
        public String query;
        public String operator;
        public String scope;
    }

    public static final class SearchTypeInfo {
        public String key;
        public String label;
        public String description;
        public String permissionKey;
        public ArrayList<String> operators = new ArrayList<String>();
    }

    public static final class SearchResultRec {
        public String matterUuid;
        public String matterLabel;
        public String documentUuid;
        public String documentTitle;
        public String partUuid;
        public String partLabel;
        public String versionUuid;
        public String versionLabel;
        public String source;
        public String mimeType;
        public String createdAt;
        public String createdBy;
        public String matchedIn;
        public String snippet;
    }

    public static final class SearchJobSnapshot {
        public String jobId;
        public String tenantUuid;
        public String requestedBy;
        public String searchType;
        public String query;
        public String operator;
        public String logic;
        public ArrayList<SearchCriterion> criteria = new ArrayList<SearchCriterion>();
        public boolean caseSensitive;
        public boolean includeMetadata;
        public boolean includeOcr;
        public String status;
        public String message;
        public String createdAt;
        public String startedAt;
        public String completedAt;
        public int processedCount;
        public int totalCount;
        public int resultCount;
        public boolean truncated;
        public int queuedAhead;
        public ArrayList<SearchResultRec> results = new ArrayList<SearchResultRec>();
    }

    private interface search_type_handler {
        SearchTypeInfo info();
        SearchExecutionResult execute(SearchContext ctx) throws Exception;
    }

    public static final class SearchExecutionResult {
        public ArrayList<SearchResultRec> results = new ArrayList<SearchResultRec>();
        public boolean truncated;
        public String message;
    }

    public static final class SearchContext {
        public final SearchJobRequest request;
        private final JobRecord job;
        private final version_ocr_companion_service ocrService;

        private SearchContext(SearchJobRequest request,
                              JobRecord job,
                              version_ocr_companion_service ocrService) {
            this.request = request;
            this.job = job;
            this.ocrService = ocrService;
        }

        public void progress(int processed, int total, String message) {
            job.processedCount = Math.max(0, processed);
            job.totalCount = Math.max(0, total);
            String m = safe(message).trim();
            if (!m.isBlank()) job.message = m;
        }

        public version_ocr_companion_service ocrService() {
            return ocrService;
        }
    }

    private static final class JobRecord {
        final String jobId = UUID.randomUUID().toString();
        final long createdAtMs = System.currentTimeMillis();
        final String createdAt = Instant.ofEpochMilli(createdAtMs).toString();
        final SearchJobRequest request;
        volatile String status = "queued";
        volatile String message = "Queued.";
        volatile String startedAt = "";
        volatile String completedAt = "";
        volatile int processedCount = 0;
        volatile int totalCount = 0;
        volatile int resultCount = 0;
        volatile boolean truncated = false;
        private final ArrayList<SearchResultRec> results = new ArrayList<SearchResultRec>();

        JobRecord(SearchJobRequest request) {
            this.request = request;
        }

        synchronized void replaceResults(List<SearchResultRec> rows, boolean truncated) {
            results.clear();
            if (rows != null) {
                for (SearchResultRec row : rows) {
                    if (row == null) continue;
                    results.add(copyResult(row));
                }
            }
            this.resultCount = results.size();
            this.truncated = truncated;
        }

        synchronized ArrayList<SearchResultRec> resultCopies(int limit) {
            int cap = limit <= 0 ? results.size() : Math.min(limit, results.size());
            ArrayList<SearchResultRec> out = new ArrayList<SearchResultRec>(cap);
            for (int i = 0; i < cap; i++) out.add(copyResult(results.get(i)));
            return out;
        }
    }

    private static final class VersionTarget {
        String matterUuid;
        String matterLabel;
        String documentUuid;
        String documentTitle;
        String partUuid;
        String partLabel;
        part_versions.VersionRec version;
    }

    private final ExecutorService workers;
    private final LinkedHashMap<String, search_type_handler> handlers = new LinkedHashMap<String, search_type_handler>();
    private final ConcurrentHashMap<String, JobRecord> jobsById = new ConcurrentHashMap<String, JobRecord>();
    private final ConcurrentLinkedDeque<String> jobOrder = new ConcurrentLinkedDeque<String>();
    private final version_ocr_companion_service ocrService = version_ocr_companion_service.defaultService();

    private search_jobs_service() {
        this.workers = Executors.newFixedThreadPool(SEARCH_WORKERS, new ThreadFactory() {
            private int seq = 0;
            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "search-jobs-worker-" + (++seq));
                t.setDaemon(true);
                return t;
            }
        });
        registerHandler(new document_part_versions_search_type());
        registerHandler(new case_conflicts_search_type());
    }

    public static search_jobs_service defaultService() {
        return Holder.INSTANCE;
    }

    public ArrayList<SearchTypeInfo> listSearchTypes() {
        ArrayList<SearchTypeInfo> out = new ArrayList<SearchTypeInfo>();
        synchronized (handlers) {
            for (search_type_handler handler : handlers.values()) {
                if (handler == null) continue;
                out.add(copyTypeInfo(handler.info()));
            }
        }
        return out;
    }

    public SearchTypeInfo getSearchType(String key) {
        String wanted = safe(key).trim().toLowerCase(Locale.ROOT);
        synchronized (handlers) {
            search_type_handler handler = handlers.get(wanted);
            return handler == null ? null : copyTypeInfo(handler.info());
        }
    }

    public boolean isTesseractAvailable() {
        return ocrService.isTesseractAvailable();
    }

    public String enqueue(SearchJobRequest request) throws Exception {
        SearchJobRequest normalized = normalizeRequest(request);
        final search_type_handler handler;
        synchronized (handlers) {
            handler = handlers.get(normalized.searchType);
        }
        if (handler == null) throw new IllegalArgumentException("Unknown search type.");

        JobRecord record = new JobRecord(normalized);
        jobsById.put(record.jobId, record);
        jobOrder.addLast(record.jobId);
        pruneJobs();

        workers.submit(() -> runJob(record, handler));
        return record.jobId;
    }

    public SearchJobSnapshot getJob(String tenantUuid, String requestedBy, String jobId, boolean includeResults) {
        String id = safe(jobId).trim();
        if (id.isBlank()) return null;
        JobRecord rec = jobsById.get(id);
        if (rec == null) return null;
        if (!jobVisibleToRequester(rec, tenantUuid, requestedBy)) return null;
        return snapshot(rec, includeResults, DEFAULT_RESULTS_PER_JOB);
    }

    public ArrayList<SearchJobSnapshot> listJobs(String tenantUuid, String requestedBy, int limit, boolean includeResults) {
        int cap = clamp(limit, 1, 100);
        ArrayList<SearchJobSnapshot> out = new ArrayList<SearchJobSnapshot>();
        ArrayDeque<String> ids = new ArrayDeque<String>(jobOrder);
        while (!ids.isEmpty() && out.size() < cap) {
            String id = ids.removeLast();
            JobRecord rec = jobsById.get(id);
            if (rec == null) continue;
            if (!jobVisibleToRequester(rec, tenantUuid, requestedBy)) continue;
            out.add(snapshot(rec, includeResults, DEFAULT_RESULTS_PER_JOB));
        }
        out.sort(Comparator.comparing((SearchJobSnapshot s) -> safe(s.createdAt)).reversed());
        return out;
    }

    private void runJob(JobRecord record, search_type_handler handler) {
        if (record == null || handler == null) return;
        record.status = "running";
        record.startedAt = Instant.now().toString();
        record.message = "Running search...";

        SearchContext ctx = new SearchContext(record.request, record, ocrService);

        try {
            SearchExecutionResult result = handler.execute(ctx);
            ArrayList<SearchResultRec> rows = result == null ? new ArrayList<SearchResultRec>() : result.results;
            boolean truncated = result != null && result.truncated;
            record.replaceResults(rows, truncated);
            record.status = "completed";
            record.completedAt = Instant.now().toString();
            if (result != null && !safe(result.message).trim().isBlank()) {
                record.message = safe(result.message).trim();
            } else {
                record.message = "Completed. " + record.resultCount + " result(s).";
            }
        } catch (Exception ex) {
            record.status = "failed";
            record.completedAt = Instant.now().toString();
            record.message = safe(ex.getMessage()).trim();
            if (record.message.isBlank()) record.message = "Search failed.";
            LOG.log(Level.WARNING, "Search job failed id=" + record.jobId + ": " + safe(ex.getMessage()), ex);
        } finally {
            pruneJobs();
        }
    }

    private SearchJobSnapshot snapshot(JobRecord rec, boolean includeResults, int resultLimit) {
        SearchJobSnapshot out = new SearchJobSnapshot();
        if (rec == null) return out;
        out.jobId = rec.jobId;
        out.tenantUuid = safe(rec.request.tenantUuid);
        out.requestedBy = safe(rec.request.requestedBy);
        out.searchType = safe(rec.request.searchType);
        out.query = safe(rec.request.query);
        out.operator = safe(rec.request.operator);
        out.logic = safe(rec.request.logic);
        out.criteria = copyCriteria(rec.request.criteria);
        out.caseSensitive = rec.request.caseSensitive;
        out.includeMetadata = rec.request.includeMetadata;
        out.includeOcr = rec.request.includeOcr;
        out.status = safe(rec.status);
        out.message = safe(rec.message);
        out.createdAt = safe(rec.createdAt);
        out.startedAt = safe(rec.startedAt);
        out.completedAt = safe(rec.completedAt);
        out.processedCount = rec.processedCount;
        out.totalCount = rec.totalCount;
        out.resultCount = rec.resultCount;
        out.truncated = rec.truncated;
        out.queuedAhead = queuedAhead(rec.jobId);
        if (includeResults) {
            out.results = rec.resultCopies(resultLimit <= 0 ? DEFAULT_RESULTS_PER_JOB : resultLimit);
        }
        return out;
    }

    private int queuedAhead(String jobId) {
        String wanted = safe(jobId).trim();
        if (wanted.isBlank()) return 0;
        int ahead = 0;
        for (String id : jobOrder) {
            if (wanted.equals(id)) break;
            JobRecord rec = jobsById.get(id);
            if (rec == null) continue;
            String status = safe(rec.status).trim().toLowerCase(Locale.ROOT);
            if ("queued".equals(status) || "running".equals(status)) ahead++;
        }
        return Math.max(0, ahead);
    }

    private void registerHandler(search_type_handler handler) {
        if (handler == null || handler.info() == null) return;
        String key = safe(handler.info().key).trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) return;
        synchronized (handlers) {
            handlers.put(key, handler);
        }
    }

    private SearchJobRequest normalizeRequest(SearchJobRequest in) {
        SearchJobRequest out = new SearchJobRequest();
        if (in == null) in = new SearchJobRequest();
        out.tenantUuid = safe(in.tenantUuid).trim();
        out.requestedBy = safe(in.requestedBy).trim();
        out.searchType = safe(in.searchType).trim().toLowerCase(Locale.ROOT);
        out.query = safe(in.query);
        out.operator = search_query_operator.fromKey(in.operator).key();
        out.logic = normalizeLogic(in.logic);
        out.caseSensitive = in.caseSensitive;
        out.includeMetadata = in.includeMetadata;
        out.includeOcr = in.includeOcr;
        out.maxResults = clamp(in.maxResults <= 0 ? DEFAULT_RESULTS_PER_JOB : in.maxResults, 1, MAX_RESULTS_PER_JOB);
        out.criteria = copyCriteria(in.criteria);

        if (out.tenantUuid.isBlank()) throw new IllegalArgumentException("Tenant is required.");
        if (out.requestedBy.isBlank()) throw new IllegalArgumentException("Request user is required.");
        if (out.searchType.isBlank()) throw new IllegalArgumentException("Search type is required.");
        if (out.criteria.isEmpty()) {
            SearchCriterion c = new SearchCriterion();
            c.query = out.query;
            c.operator = out.operator;
            c.scope = "any";
            SearchCriterion normalized = copyCriterion(c);
            if (normalized == null) throw new IllegalArgumentException("At least one search criterion is required.");
            out.criteria.add(normalized);
        }
        if (out.criteria.size() > 10) {
            throw new IllegalArgumentException("Maximum 10 search criteria are allowed per job.");
        }
        // Keep legacy top-level fields as first criterion for compatibility in UIs/API.
        SearchCriterion first = out.criteria.get(0);
        out.query = safe(first == null ? "" : first.query);
        out.operator = search_query_operator.fromKey(first == null ? "" : first.operator).key();

        if (!out.includeMetadata && !out.includeOcr) {
            throw new IllegalArgumentException("Select at least one search source: metadata and/or OCR.");
        }
        return out;
    }

    private boolean jobVisibleToRequester(JobRecord rec, String tenantUuid, String requestedBy) {
        if (rec == null || rec.request == null) return false;
        String tu = safe(tenantUuid).trim();
        String ru = safe(requestedBy).trim();
        if (tu.isBlank() || ru.isBlank()) return false;
        return tu.equals(safe(rec.request.tenantUuid).trim())
                && ru.equalsIgnoreCase(safe(rec.request.requestedBy).trim());
    }

    private void pruneJobs() {
        long cutoff = System.currentTimeMillis() - JOB_RETENTION_MS;
        while (jobOrder.size() > MAX_JOBS_RETAINED) {
            String id = jobOrder.pollFirst();
            if (id == null) break;
            jobsById.remove(id);
        }
        for (String id : new ArrayList<String>(jobOrder)) {
            JobRecord rec = jobsById.get(id);
            if (rec == null) {
                jobOrder.remove(id);
                continue;
            }
            if (rec.createdAtMs < cutoff) {
                jobsById.remove(id);
                jobOrder.remove(id);
            }
        }
    }

    private static SearchTypeInfo copyTypeInfo(SearchTypeInfo in) {
        SearchTypeInfo out = new SearchTypeInfo();
        if (in == null) return out;
        out.key = safe(in.key);
        out.label = safe(in.label);
        out.description = safe(in.description);
        out.permissionKey = safe(in.permissionKey);
        out.operators = new ArrayList<String>(in.operators == null ? List.of() : in.operators);
        return out;
    }

    private static SearchResultRec copyResult(SearchResultRec in) {
        SearchResultRec out = new SearchResultRec();
        if (in == null) return out;
        out.matterUuid = safe(in.matterUuid);
        out.matterLabel = safe(in.matterLabel);
        out.documentUuid = safe(in.documentUuid);
        out.documentTitle = safe(in.documentTitle);
        out.partUuid = safe(in.partUuid);
        out.partLabel = safe(in.partLabel);
        out.versionUuid = safe(in.versionUuid);
        out.versionLabel = safe(in.versionLabel);
        out.source = safe(in.source);
        out.mimeType = safe(in.mimeType);
        out.createdAt = safe(in.createdAt);
        out.createdBy = safe(in.createdBy);
        out.matchedIn = safe(in.matchedIn);
        out.snippet = safe(in.snippet);
        return out;
    }

    private static ArrayList<SearchCriterion> copyCriteria(List<SearchCriterion> in) {
        ArrayList<SearchCriterion> out = new ArrayList<SearchCriterion>();
        if (in == null) return out;
        for (SearchCriterion row : in) {
            SearchCriterion c = copyCriterion(row);
            if (c == null) continue;
            out.add(c);
        }
        return out;
    }

    private static SearchCriterion copyCriterion(SearchCriterion in) {
        if (in == null) return null;
        SearchCriterion out = new SearchCriterion();
        out.query = safe(in.query);
        out.operator = search_query_operator.fromKey(in.operator).key();
        out.scope = normalizeScope(in.scope);
        if (safe(out.query).trim().isBlank()) return null;
        return out;
    }

    private static int clamp(int v, int min, int max) {
        int n = Math.max(min, v);
        return Math.min(max, n);
    }

    private static String normalizeLogic(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("and".equals(v)) return "and";
        return "or";
    }

    private static String normalizeScope(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("metadata".equals(v)) return "metadata";
        if ("ocr".equals(v)) return "ocr";
        return "any";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class document_part_versions_search_type implements search_type_handler {
        @Override
        public SearchTypeInfo info() {
            SearchTypeInfo out = new SearchTypeInfo();
            out.key = "document_part_versions";
            out.label = "Document Part Versions";
            out.description = "Search part version metadata and companion OCR/extracted text.";
            out.permissionKey = "documents.access";
            LinkedHashSet<String> ops = new LinkedHashSet<String>();
            for (search_query_operator op : search_query_operator.values()) {
                ops.add(op.key());
            }
            out.operators.addAll(ops);
            return out;
        }

        @Override
        public SearchExecutionResult execute(SearchContext ctx) throws Exception {
            SearchExecutionResult out = new SearchExecutionResult();
            if (ctx == null || ctx.request == null) return out;
            SearchJobRequest req = ctx.request;

            ArrayList<VersionTarget> targets = collectTargets(req.tenantUuid);
            int total = targets.size();
            int processed = 0;
            int ocrErrors = 0;
            ctx.progress(0, total, "Preparing search.");
            ArrayList<SearchCriterion> criteria = req.criteria == null
                    ? new ArrayList<SearchCriterion>()
                    : new ArrayList<SearchCriterion>(req.criteria);
            String logic = normalizeLogic(req.logic);
            for (VersionTarget target : targets) {
                processed++;
                if (target == null || target.version == null) {
                    ctx.progress(processed, total, "Scanning versions...");
                    continue;
                }

                String metadataText = req.includeMetadata ? metadataBlob(target) : "";
                String ocrText = "";
                if (req.includeOcr) {
                    try {
                        version_ocr_companion_service.CompanionRec companion = ctx.ocrService().ensureCompanion(
                                req.tenantUuid,
                                target.matterUuid,
                                target.documentUuid,
                                target.partUuid,
                                target.version
                        );
                        ocrText = companion == null ? "" : safe(companion.fullText);
                    } catch (Exception ex) {
                        ocrErrors++;
                        if (ocrErrors <= 3) {
                            LOG.log(Level.FINE, "OCR companion generation skipped: " + safe(ex.getMessage()), ex);
                        }
                    }
                }

                CriteriaEval eval = evaluateCriteria(
                        criteria,
                        logic,
                        metadataText,
                        ocrText,
                        req.includeMetadata,
                        req.includeOcr,
                        req.caseSensitive
                );

                if (eval.matches) {
                    SearchResultRec hit = new SearchResultRec();
                    hit.matterUuid = target.matterUuid;
                    hit.matterLabel = target.matterLabel;
                    hit.documentUuid = target.documentUuid;
                    hit.documentTitle = target.documentTitle;
                    hit.partUuid = target.partUuid;
                    hit.partLabel = target.partLabel;
                    hit.versionUuid = safe(target.version.uuid);
                    hit.versionLabel = safe(target.version.versionLabel);
                    hit.source = safe(target.version.source);
                    hit.mimeType = safe(target.version.mimeType);
                    hit.createdAt = safe(target.version.createdAt);
                    hit.createdBy = safe(target.version.createdBy);
                    hit.matchedIn = safe(eval.matchedIn);
                    hit.snippet = safe(eval.snippet);
                    out.results.add(hit);
                    if (out.results.size() >= req.maxResults) {
                        out.truncated = true;
                        break;
                    }
                }

                if (processed == 1 || processed % 5 == 0 || processed == total) {
                    ctx.progress(processed, total, "Scanning versions...");
                }
            }

            if (out.truncated) {
                out.message = "Completed with " + out.results.size() + " result(s). Results truncated at max limit.";
            } else if (ocrErrors > 0) {
                out.message = "Completed with " + out.results.size() + " result(s). OCR warnings: " + ocrErrors + ".";
            } else {
                out.message = "Completed with " + out.results.size() + " result(s).";
            }
            ctx.progress(processed, total, out.message);
            return out;
        }

        private static ArrayList<VersionTarget> collectTargets(String tenantUuid) throws Exception {
            String tu = safe(tenantUuid).trim();
            ArrayList<VersionTarget> out = new ArrayList<VersionTarget>();
            if (tu.isBlank()) return out;

            matters matterStore = matters.defaultStore();
            documents docStore = documents.defaultStore();
            document_parts partStore = document_parts.defaultStore();
            part_versions versionStore = part_versions.defaultStore();

            List<matters.MatterRec> mattersRows = matterStore.listAll(tu);
            for (matters.MatterRec matter : mattersRows) {
                if (matter == null || matter.trashed || !matter.enabled) continue;
                String matterUuid = safe(matter.uuid).trim();
                if (matterUuid.isBlank()) continue;

                List<documents.DocumentRec> docRows = docStore.listAll(tu, matterUuid);
                for (documents.DocumentRec doc : docRows) {
                    if (doc == null || doc.trashed) continue;
                    String docUuid = safe(doc.uuid).trim();
                    if (docUuid.isBlank()) continue;

                    List<document_parts.PartRec> partRows = partStore.listAll(tu, matterUuid, docUuid);
                    for (document_parts.PartRec part : partRows) {
                        if (part == null || part.trashed) continue;
                        String partUuid = safe(part.uuid).trim();
                        if (partUuid.isBlank()) continue;

                        List<part_versions.VersionRec> versionRows = versionStore.listAll(tu, matterUuid, docUuid, partUuid);
                        for (part_versions.VersionRec version : versionRows) {
                            if (version == null) continue;
                            VersionTarget target = new VersionTarget();
                            target.matterUuid = matterUuid;
                            target.matterLabel = safe(matter.label);
                            target.documentUuid = docUuid;
                            target.documentTitle = safe(doc.title);
                            target.partUuid = partUuid;
                            target.partLabel = safe(part.label);
                            target.version = version;
                            out.add(target);
                        }
                    }
                }
            }
            return out;
        }

        private static final class CriteriaEval {
            boolean matches = false;
            String matchedIn = "";
            String snippet = "";
        }

        private static CriteriaEval evaluateCriteria(List<SearchCriterion> criteria,
                                                     String logic,
                                                     String metadataText,
                                                     String ocrText,
                                                     boolean includeMetadata,
                                                     boolean includeOcr,
                                                     boolean caseSensitive) {
            CriteriaEval out = new CriteriaEval();
            List<SearchCriterion> rows = criteria == null ? List.of() : criteria;
            if (rows.isEmpty()) return out;

            boolean andMode = "and".equals(normalizeLogic(logic));
            boolean aggregate = andMode;
            boolean seen = false;

            for (SearchCriterion row : rows) {
                SearchCriterion c = copyCriterion(row);
                if (c == null) continue;
                seen = true;

                search_query_operator operator = search_query_operator.fromKey(c.operator);
                String scope = normalizeScope(c.scope);
                String query = safe(c.query).trim();

                boolean allowMetadata = includeMetadata && ("any".equals(scope) || "metadata".equals(scope));
                boolean allowOcr = includeOcr && ("any".equals(scope) || "ocr".equals(scope));
                boolean metadataMatch = allowMetadata && operator.matches(metadataText, query, caseSensitive);
                boolean ocrMatch = allowOcr && operator.matches(ocrText, query, caseSensitive);
                boolean criterionMatch = metadataMatch || ocrMatch;

                if (andMode) {
                    aggregate = aggregate && criterionMatch;
                    if (!criterionMatch) {
                        out.matches = false;
                        out.matchedIn = "";
                        out.snippet = "";
                        return out;
                    }
                } else {
                    aggregate = aggregate || criterionMatch;
                }

                if (criterionMatch && out.snippet.isBlank()) {
                    if (ocrMatch) {
                        out.snippet = snippet(ocrText, query, caseSensitive);
                    } else {
                        out.snippet = snippet(metadataText, query, caseSensitive);
                    }
                }

                if (criterionMatch) {
                    if (metadataMatch && ocrMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "metadata+ocr");
                    else if (metadataMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "metadata");
                    else if (ocrMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "ocr");
                }
            }

            out.matches = seen && aggregate;
            if (out.matches && out.matchedIn.isBlank()) out.matchedIn = "metadata";
            return out;
        }

        private static String mergeMatchedIn(String existing, String next) {
            String a = safe(existing).trim().toLowerCase(Locale.ROOT);
            String b = safe(next).trim().toLowerCase(Locale.ROOT);
            if (a.isBlank()) return b;
            if (b.isBlank()) return a;
            if (a.equals(b)) return a;
            if ("metadata+ocr".equals(a) || "metadata+ocr".equals(b)) return "metadata+ocr";
            if ((a.contains("metadata") && b.contains("ocr")) || (a.contains("ocr") && b.contains("metadata"))) {
                return "metadata+ocr";
            }
            return b;
        }

        private static String metadataBlob(VersionTarget target) {
            if (target == null || target.version == null) return "";
            part_versions.VersionRec v = target.version;
            StringBuilder sb = new StringBuilder(512);
            appendField(sb, target.matterLabel);
            appendField(sb, target.documentTitle);
            appendField(sb, target.partLabel);
            appendField(sb, v.versionLabel);
            appendField(sb, v.source);
            appendField(sb, v.mimeType);
            appendField(sb, v.createdBy);
            appendField(sb, v.notes);
            appendField(sb, v.storagePath);
            String fileName = fileName(v.storagePath);
            appendField(sb, fileName);
            return sb.toString();
        }

        private static String fileName(String storagePath) {
            try {
                Path p = pdf_redaction_service.resolveStoragePath(storagePath);
                if (p == null || p.getFileName() == null) return "";
                return safe(p.getFileName().toString());
            } catch (Exception ignored) {
                return "";
            }
        }

        private static void appendField(StringBuilder sb, String value) {
            String v = safe(value).trim();
            if (v.isBlank()) return;
            if (sb.length() > 0) sb.append('\n');
            sb.append(v);
        }

        private static String snippet(String haystack, String query, boolean caseSensitive) {
            String original = safe(haystack).replaceAll("\\s+", " ").trim();
            if (original.isBlank()) return "";
            String q = safe(query).trim();
            if (q.isBlank()) {
                return original.length() <= 180 ? original : original.substring(0, 180) + "...";
            }

            String probe = original;
            String needle = q;
            if (!caseSensitive) {
                probe = probe.toLowerCase(Locale.ROOT);
                needle = needle.toLowerCase(Locale.ROOT);
            }

            int idx = probe.indexOf(needle);
            if (idx < 0) {
                String[] terms = q.split("\\s+");
                for (String term : terms) {
                    String t = safe(term).trim();
                    if (t.isBlank()) continue;
                    String tn = caseSensitive ? t : t.toLowerCase(Locale.ROOT);
                    idx = probe.indexOf(tn);
                    if (idx >= 0) {
                        needle = tn;
                        break;
                    }
                }
            }
            if (idx < 0) idx = 0;

            int start = Math.max(0, idx - 70);
            int end = Math.min(original.length(), idx + Math.max(needle.length(), 1) + 90);
            String core = original.substring(start, end).trim();
            if (start > 0) core = "..." + core;
            if (end < original.length()) core = core + "...";
            return core;
        }
    }

    private static final class case_conflicts_search_type implements search_type_handler {
        @Override
        public SearchTypeInfo info() {
            SearchTypeInfo out = new SearchTypeInfo();
            out.key = "case_conflicts";
            out.label = "Case Conflicts";
            out.description = "Search case conflicts.xml entries with NLP/contact-derived entities.";
            out.permissionKey = "conflicts.access";
            LinkedHashSet<String> ops = new LinkedHashSet<String>();
            for (search_query_operator op : search_query_operator.values()) {
                ops.add(op.key());
            }
            out.operators.addAll(ops);
            return out;
        }

        @Override
        public SearchExecutionResult execute(SearchContext ctx) throws Exception {
            SearchExecutionResult out = new SearchExecutionResult();
            if (ctx == null || ctx.request == null) return out;
            SearchJobRequest req = ctx.request;

            List<matters.MatterRec> mattersRows = matters.defaultStore().listAll(req.tenantUuid);
            ArrayList<matters.MatterRec> activeMatters = new ArrayList<matters.MatterRec>();
            for (matters.MatterRec row : mattersRows) {
                if (row == null) continue;
                String mu = safe(row.uuid).trim();
                if (mu.isBlank()) continue;
                if (!row.enabled || row.trashed) continue;
                activeMatters.add(row);
            }

            int total = activeMatters.size();
            int processed = 0;
            int scanWarnings = 0;
            ctx.progress(0, total, "Preparing conflict search.");

            ArrayList<SearchCriterion> criteria = req.criteria == null
                    ? new ArrayList<SearchCriterion>()
                    : new ArrayList<SearchCriterion>(req.criteria);
            String logic = normalizeLogic(req.logic);

            matter_conflicts store = matter_conflicts.defaultStore();
            conflicts_scan_service scanner = conflicts_scan_service.defaultService();

            for (matters.MatterRec matter : activeMatters) {
                processed++;
                String matterUuid = safe(matter == null ? "" : matter.uuid).trim();
                if (matterUuid.isBlank()) {
                    ctx.progress(processed, total, "Scanning conflicts...");
                    continue;
                }

                try {
                    scanner.scanMatter(req.tenantUuid, matterUuid, true);
                } catch (Exception ex) {
                    scanWarnings++;
                    if (scanWarnings <= 5) {
                        LOG.log(Level.FINE,
                                "Conflict scan warning matter=" + matterUuid + ": " + safe(ex.getMessage()),
                                ex);
                    }
                }

                matter_conflicts.FileRec file;
                try {
                    file = store.read(req.tenantUuid, matterUuid);
                } catch (Exception ex) {
                    scanWarnings++;
                    if (scanWarnings <= 5) {
                        LOG.log(Level.FINE,
                                "Unable to read conflicts.xml for matter=" + matterUuid + ": " + safe(ex.getMessage()),
                                ex);
                    }
                    ctx.progress(processed, total, "Scanning conflicts...");
                    continue;
                }

                ArrayList<matter_conflicts.ConflictEntry> entries = file == null
                        ? new ArrayList<matter_conflicts.ConflictEntry>()
                        : new ArrayList<matter_conflicts.ConflictEntry>(file.entries);
                for (matter_conflicts.ConflictEntry entry : entries) {
                    if (entry == null) continue;
                    String metadataText = req.includeMetadata ? metadataBlob(matter, entry) : "";
                    String ocrText = req.includeOcr ? ocrBlob(entry) : "";

                    CriteriaEval eval = evaluateCriteria(
                            criteria,
                            logic,
                            metadataText,
                            ocrText,
                            req.includeMetadata,
                            req.includeOcr,
                            req.caseSensitive
                    );
                    if (!eval.matches) continue;

                    SearchResultRec hit = new SearchResultRec();
                    hit.matterUuid = matterUuid;
                    hit.matterLabel = safe(matter.label);
                    hit.documentUuid = "";
                    hit.documentTitle = safe(entry.displayName);
                    hit.partUuid = "";
                    hit.partLabel = safe(entry.entityType);
                    hit.versionUuid = safe(entry.uuid);
                    hit.versionLabel = safe(entry.sourceTags);
                    hit.source = "conflicts.xml";
                    hit.mimeType = "application/xml";
                    hit.createdAt = safe(entry.lastSeenAt);
                    hit.createdBy = "";
                    hit.matchedIn = safe(eval.matchedIn);
                    hit.snippet = safe(eval.snippet);
                    out.results.add(hit);
                    if (out.results.size() >= req.maxResults) {
                        out.truncated = true;
                        break;
                    }
                }

                if (out.truncated) break;
                if (processed == 1 || processed % 3 == 0 || processed == total) {
                    ctx.progress(processed, total, "Scanning conflicts...");
                }
            }

            if (out.truncated) {
                out.message = "Completed with " + out.results.size() + " conflict result(s). Results truncated.";
            } else if (scanWarnings > 0) {
                out.message = "Completed with " + out.results.size() + " conflict result(s). Warnings: " + scanWarnings + ".";
            } else {
                out.message = "Completed with " + out.results.size() + " conflict result(s).";
            }
            ctx.progress(processed, total, out.message);
            return out;
        }

        private static final class CriteriaEval {
            boolean matches = false;
            String matchedIn = "";
            String snippet = "";
        }

        private static CriteriaEval evaluateCriteria(List<SearchCriterion> criteria,
                                                     String logic,
                                                     String metadataText,
                                                     String ocrText,
                                                     boolean includeMetadata,
                                                     boolean includeOcr,
                                                     boolean caseSensitive) {
            CriteriaEval out = new CriteriaEval();
            List<SearchCriterion> rows = criteria == null ? List.of() : criteria;
            if (rows.isEmpty()) return out;

            boolean andMode = "and".equals(normalizeLogic(logic));
            boolean aggregate = andMode;
            boolean seen = false;

            for (SearchCriterion row : rows) {
                SearchCriterion c = copyCriterion(row);
                if (c == null) continue;
                seen = true;

                search_query_operator operator = search_query_operator.fromKey(c.operator);
                String scope = normalizeScope(c.scope);
                String query = safe(c.query).trim();

                boolean allowMetadata = includeMetadata && ("any".equals(scope) || "metadata".equals(scope));
                boolean allowOcr = includeOcr && ("any".equals(scope) || "ocr".equals(scope));
                boolean metadataMatch = allowMetadata && operator.matches(metadataText, query, caseSensitive);
                boolean ocrMatch = allowOcr && operator.matches(ocrText, query, caseSensitive);
                boolean criterionMatch = metadataMatch || ocrMatch;

                if (andMode) {
                    aggregate = aggregate && criterionMatch;
                    if (!criterionMatch) {
                        out.matches = false;
                        out.matchedIn = "";
                        out.snippet = "";
                        return out;
                    }
                } else {
                    aggregate = aggregate || criterionMatch;
                }

                if (criterionMatch && out.snippet.isBlank()) {
                    if (ocrMatch) out.snippet = snippet(ocrText, query, caseSensitive);
                    else out.snippet = snippet(metadataText, query, caseSensitive);
                }
                if (criterionMatch) {
                    if (metadataMatch && ocrMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "metadata+ocr");
                    else if (metadataMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "metadata");
                    else if (ocrMatch) out.matchedIn = mergeMatchedIn(out.matchedIn, "ocr");
                }
            }

            out.matches = seen && aggregate;
            if (out.matches && out.matchedIn.isBlank()) out.matchedIn = "metadata";
            return out;
        }

        private static String metadataBlob(matters.MatterRec matter, matter_conflicts.ConflictEntry entry) {
            StringBuilder sb = new StringBuilder(512);
            appendField(sb, safe(matter == null ? "" : matter.label));
            appendField(sb, safe(entry == null ? "" : entry.entityType));
            appendField(sb, safe(entry == null ? "" : entry.displayName));
            appendField(sb, safe(entry == null ? "" : entry.normalizedName));
            appendField(sb, safe(entry == null ? "" : entry.sourceTags));
            appendField(sb, safe(entry == null ? "" : entry.sourceRefs));
            appendField(sb, safe(entry == null ? "" : entry.linkedContactUuids));
            appendField(sb, safe(entry == null ? "" : entry.notes));
            return sb.toString();
        }

        private static String ocrBlob(matter_conflicts.ConflictEntry entry) {
            StringBuilder sb = new StringBuilder(256);
            appendField(sb, safe(entry == null ? "" : entry.displayName));
            appendField(sb, safe(entry == null ? "" : entry.normalizedName));
            appendField(sb, safe(entry == null ? "" : entry.notes));
            return sb.toString();
        }

        private static void appendField(StringBuilder sb, String value) {
            String v = safe(value).trim();
            if (v.isBlank()) return;
            if (sb.length() > 0) sb.append('\n');
            sb.append(v);
        }

        private static String mergeMatchedIn(String existing, String next) {
            String a = safe(existing).trim().toLowerCase(Locale.ROOT);
            String b = safe(next).trim().toLowerCase(Locale.ROOT);
            if (a.isBlank()) return b;
            if (b.isBlank()) return a;
            if (a.equals(b)) return a;
            if ("metadata+ocr".equals(a) || "metadata+ocr".equals(b)) return "metadata+ocr";
            if ((a.contains("metadata") && b.contains("ocr")) || (a.contains("ocr") && b.contains("metadata"))) {
                return "metadata+ocr";
            }
            return b;
        }

        private static String snippet(String haystack, String query, boolean caseSensitive) {
            String original = safe(haystack).replaceAll("\\s+", " ").trim();
            if (original.isBlank()) return "";
            String q = safe(query).trim();
            if (q.isBlank()) {
                return original.length() <= 180 ? original : original.substring(0, 180) + "...";
            }

            String probe = original;
            String needle = q;
            if (!caseSensitive) {
                probe = probe.toLowerCase(Locale.ROOT);
                needle = needle.toLowerCase(Locale.ROOT);
            }

            int idx = probe.indexOf(needle);
            if (idx < 0) {
                String[] terms = q.split("\\s+");
                for (String term : terms) {
                    String t = safe(term).trim();
                    if (t.isBlank()) continue;
                    String tn = caseSensitive ? t : t.toLowerCase(Locale.ROOT);
                    idx = probe.indexOf(tn);
                    if (idx >= 0) {
                        needle = tn;
                        break;
                    }
                }
            }
            if (idx < 0) idx = 0;

            int start = Math.max(0, idx - 70);
            int end = Math.min(original.length(), idx + Math.max(needle.length(), 1) + 90);
            String core = original.substring(start, end).trim();
            if (start > 0) core = "..." + core;
            if (end < original.length()) core = core + "...";
            return core;
        }
    }
}
