(ns hotel.problems.protocol
  "PORT: where post-commit failures and other operational concerns get
   RECORDED so they cannot vanish silently. Adapters: in-memory (test),
   stderr-json (default prod). Real installs swap in a Sentry/Datadog
   adapter without touching the application.

   Each call records ONE problem as a structured map. The shape is
   open by design so callers can attach whatever context is useful;
   `:problem` (a keyword) is the one required key.")

(defprotocol ProblemSink
  (record-problem! [this problem]
    "Record one structured problem. `problem` is a map with at minimum
     {:problem keyword}; callers add :correlation-id, :context, etc."))
