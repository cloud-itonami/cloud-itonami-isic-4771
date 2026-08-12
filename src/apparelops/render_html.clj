(ns apparelops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL ISIC-4771 apparel/footwear/leather-goods retail
  OperationActor (`apparelops.operation/build` -> a compiled langgraph-clj
  StateGraph) over the REAL seeded store (`apparelops.store/seed-db`),
  through the REAL ApparelRetailGovernor (`apparelops.governor/check`) and
  the REAL rollout phase gate (`apparelops.phase/gate`), and renders
  whatever those actually produced. Nothing on this page is written by
  hand:

    - every store and vendor row is read back out of the store after the
      run (`store/all-store-records`, `store/all-vendor-records`,
      `store/coordination-log`, `store/ledger`) -- every store name,
      vendor name and id below comes from `apparelops.store/demo-data`,
      never from this namespace,
    - every HARD-hold rule name and every violation detail string is the
      governor's own `:violations` entry off the ledger fact -- this
      namespace holds no rule text of its own,
    - the phase table is derived from `apparelops.phase/phases` and the
      governor-configuration table from `apparelops.governor` public
      vars,
    - the only prose this namespace authors is the per-request
      `:exercises` note describing WHY that request is driven.

  Every request subject is a store id that `store/demo-data` itself
  seeds (`store-1` `store-2` `store-3`), a vendor id it seeds
  (`vendor-1` `vendor-2`), or -- for the unregistered-store HARD hold --
  a deliberately absent id (`store-99`), whose whole point is that the
  store lookup misses.

  Deterministic: no clock, no randomness, no network, and every map/set
  rendered is sorted before it reaches the page. Re-running writes a
  byte-identical file.

  Styling is `jp-go-dds.skin/dds+skin` -- the workspace's base design
  system (DADS) plus its compatibility skin for exactly this console's
  class vocabulary. This repo carries no CSS of its own.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [apparelops.advisor :as advisor]
            [apparelops.governor :as governor]
            [apparelops.operation :as op]
            [apparelops.phase :as phase]
            [apparelops.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :apparel-store-coordinator
   :phase phase/default-phase})

(defn- at-phase [n] (assoc coordinator :phase n))

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  graph while it is paused at `:request-approval` (the graph is compiled
  with `interrupt-before #{:request-approval}`). `:advisor :drifting`
  selects the second actor built below -- same store, but an advisor that
  claims a direct actuation instead of a proposal."
  [{:tid "t01"
    :exercises "Sales/returns/alterations logging against a registered + verified store at phase 3. Governor-clean and :log-sales-record is in phase 3's :auto set -> auto-commit, no human involved."
    :request {:op :log-sales-record :store-id "store-1"
              :patch {:units-sold 27 :returns 2 :alterations-requested 4
                      :stock-count-delta -29}}}

   {:tid "t02"
    :exercises "The SAME op at phase 1 (assisted-logging). The governor is equally clean, but phase 1 has an empty :auto set, so the phase gate downgrades commit -> escalate (:phase-approval). The human coordinator approves."
    :context (at-phase 1)
    :request {:op :log-sales-record :store-id "store-1"
              :patch {:units-sold 19 :returns 1 :alterations-requested 2
                      :stock-count-delta -20}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t03"
    :exercises "Floor-staff / fitting-room roster proposal at phase 3. Governor-clean and auto-eligible -> auto-commit."
    :request {:op :schedule-staffing-operation :store-id "store-1"
              :patch {:shift "weekend-fitting-room" :date "2026-07-20"
                      :window "10:00-18:00"}}}

   {:tid "t04"
    :exercises "Supply-order coordination naming a registered + verified vendor, below the governor's own supply-cost threshold -> auto-commit at phase 3."
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "seasonal footwear restock" :quantity 60
                      :estimated-cost 480.0 :vendor-id "vendor-1"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t05"
    :exercises "The same shape, ABOVE the supply-cost threshold. The governor marks it high-stakes on the cost it re-reads from the drafted value, so it escalates even at phase 3. The human approves."
    :request {:op :coordinate-supply-order :store-id "store-2"
              :patch {:item "leather outerwear collection" :quantity 40
                      :estimated-cost 3200.0 :vendor-id "vendor-1"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t06"
    :exercises "Quality-concern flag. ALWAYS escalates -- it is absent from every phase's :auto set AND is in the governor's always-escalate set, two independent layers. The human approves."
    :request {:op :flag-quality-concern :store-id "store-2"
              :patch {:concern "returned handbag: stitching separating at the strap anchor and the leather-content label may not match the item"
                      :confidence 0.9}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t07"
    :exercises "A governor-CLEAN quality-concern flag that the human VETOES. Distinct from a HARD hold: compliance cleared it, a person did not. Lands on the ledger as :approval-rejected with basis :approver-rejected."
    :request {:op :flag-quality-concern :store-id "store-1"
              :patch {:concern "shipment of denim jackets arrived with mismatched size labels on 3 units"
                      :confidence 0.82}}
    :approval {:status :rejected :by "coord-1"}}

   {:tid "t08"
    :exercises "A store id that is not in the store directory at all. The governor re-derives registration from the store record, never from the request. HARD hold -- never reaches a human."
    :request {:op :log-sales-record :store-id "store-99"
              :patch {:units-sold 0}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t09"
    :exercises "A store that IS registered but is not yet license-verified. Both flags are required, and both are read off the store's own record. HARD hold."
    :request {:op :log-sales-record :store-id "store-3"
              :patch {:units-sold 10}}}

   {:tid "t10"
    :exercises "Supply order naming a registered-but-unverified vendor. The same 'ground truth, not self-report' discipline, reapplied to the supply-chain counterparty. HARD hold."
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "imported handbags" :quantity 15
                      :estimated-cost 300.0 :vendor-id "vendor-2"}}}

   {:tid "t11"
    :exercises "Supply order that names NO vendor at all. A missing counterparty is the same failure as an unverified one, not a lenient default. HARD hold."
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "unbranded leather belts" :quantity 24
                      :estimated-cost 210.0}}}

   {:tid "t12"
    :exercises "A compromised advisor that returns :effect :commit -- a claim to actuate directly, outside governance. The governor rejects the effect itself, not merely its confidence. HARD hold."
    :advisor :drifting
    :request {:op :schedule-staffing-operation :store-id "store-1"
              :patch {:shift "weekday-fitting-room" :date "2026-07-22"}}
    :approval {:status :approved :by "coord-1"}}

   {:tid "t13"
    :exercises "An advisor that has drifted into finalizing a quality dispute and a counterfeit-authenticity determination. Permanently out of this actor's charter -- scanned across the whole proposal, never trusting its own framing. HARD hold."
    :request {:op :log-sales-record :store-id "store-1"
              :out-of-scope? true
              :patch {}}}

   {:tid "t14"
    :exercises "An op outside the closed four-op allowlist -- setting a shelf price, which this actor never does. The op allowlist and the proposal-effect check both reject it. HARD hold."
    :request {:op :set-shelf-price :store-id "store-1"
              :patch {:unit-price 8900}}}

   {:tid "t15"
    :exercises "Phase 0 (read-only). The governor is clean and the store is verified, but no op may write at all in this phase, so the phase gate holds it (:phase-disabled) with no governor violation of its own."
    :context (at-phase 0)
    :request {:op :log-sales-record :store-id "store-1"
              :patch {:units-sold 5}}}

   {:tid "t16"
    :exercises "Phase 1 (assisted-logging) only enables sales-record logging. A perfectly clean supply order naming a verified vendor is still held, because that op is not yet enabled at this phase."
    :context (at-phase 1)
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "wool scarves restock" :quantity 30
                      :estimated-cost 260.0 :vendor-id "vendor-1"}}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actors {:keys [tid request approval context] :as scenario}]
  (let [actor (get actors (:advisor scenario :default))
        ctx (or context coordinator)
        r1 (g/run* actor {:request request :context ctx} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :context ctx
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor (plus one variant whose
  advisor drifts into a direct actuation), drives every scenario against
  the SAME store. Returns {:db store :runs [..]}."
  []
  (let [db (store/seed-db)
        actors {:default (op/build db)
                :drifting (op/build db
                                    {:advisor (reify advisor/Advisor
                                                (-advise [_ _ request]
                                                  (assoc (advisor/infer nil request)
                                                         :effect :commit)))})}]
    {:db db :runs (mapv #(drive! actors %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"critical\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation order."
  [coll]
  (if (seq coll) (str/join " " (map code coll)) "—"))

(defn- kw-codes
  "Render a SET of keywords. Sorted, because a set has no order and an
  unsorted render would make the output non-deterministic."
  [coll]
  (if (seq coll) (str/join " " (map code (sort-by str coll))) "—"))

(defn- kv-pairs
  "Render a map's entries sorted by key name. A map has no dependable
  order either, so it is sorted for the same reason."
  [m]
  (if (seq m)
    (str/join " · " (for [[k v] (sort-by (comp str key) m)]
                      (str (code k) " " (esc v))))
    "—"))

(defn- tr [& cells]
  (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- ledger views -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds
  "Every HARD hold the governor / phase gate actually wrote."
  [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number is a count over the actor's own append-only ledger after driving "
               (count runs) " requests through " (code "apparelops.operation/build") ".")
          (str
           (table ["Measure" "Count"]
                  [(tr "requests driven" (esc (count runs)))
                   (tr "ledger facts" (esc (count led)))
                   (tr "commits" (str "<span class=\"ok\">" (esc (n :committed)) "</span>"))
                   (tr "governor HARD holds"
                       (str "<span class=\"critical\">" (esc (n :governor-hold)) "</span>"))
                   (tr "human rejections"
                       (str "<span class=\"critical\">" (esc (n :approval-rejected)) "</span>"))
                   (tr "human approvals granted"
                       (esc (count (filter #(= :approved (:human %)) runs))))
                   (tr "committed coordination records"
                       (esc (count (store/coordination-log db))))])
           "<p class=\"muted\"><code>:advisor-proposal</code>, <code>:approval-requested</code> and "
           "<code>:approval-granted</code> are emitted to the graph's in-memory <code>:audit</code> "
           "channel only — <code>apparelops.operation</code> never appends them to the store ledger, "
           "so they are not facts this page counts. An approved request is visible as the "
           "<code>:committed</code> fact it produced.</p>"))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (codes (map :rule (:violations verdict))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one " (code "langgraph.graph/run*") " over the compiled actor. The "
             "governor column is the verdict map the governor itself returned; the human column "
             "is the decision handed back to the graph while it was paused at "
             (code ":request-approval") ".")
        (table ["Thread" "Phase" "Op" "Store" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request context escalation exercises] :as r} runs]
                 (tr (code tid)
                     (esc (:phase context))
                     (code (:op request))
                     (code (:store-id request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation " (code reason)
                                 "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "HARD holds written to the ledger"
          (str "Each row is one violation on a " (code ":governor-hold") " fact. The rule name and "
               "the detail text are the governor's own " (code ":violations") " entries — this page "
               "holds no rule text of its own. A hold produced purely by the rollout phase gate "
               "carries no governor violation, and shows its " (code ":phase-reason") " instead.")
          (table ["#" "Rule" "Op" "Store" "Confidence" "Governor's own detail"]
                 (mapcat
                  (fn [[i h]]
                    (if (seq (:violations h))
                      (for [v (:violations h)]
                        (tr (esc i)
                            (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                            (code (:op h)) (code (:store-id h)) (fmt (:confidence h))
                            (esc (:detail v))))
                      [(tr (esc i)
                           (str "<span class=\"critical\">" (esc (:phase-reason h)) "</span>")
                           (code (:op h)) (code (:store-id h)) (fmt (:confidence h))
                           (str "<span class=\"muted\">phase gate — op not enabled at phase "
                                (esc (:phase h)) "</span>"))]))
                  (map-indexed (fn [i h] [(inc i) h]) hs))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Store" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:store-id r))
                         (codes (:basis r)) (fmt (:confidence r)))))))))

(defn- phase-section []
  (card "Rollout phase gate"
        (str "Derived from " (code "apparelops.phase/phases") ". A governor HOLD always stays a "
             "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
             "the governor is clean. This run's default phase is "
             (code phase/default-phase) ".")
        (table (into ["Op"] (for [p (sort (keys phase/phases))]
                              (str "phase " p " (" (:label (get phase/phases p)) ")")))
               (for [o (sort-by str governor/allowed-ops)]
                 (apply tr (code o)
                        (for [p (sort (keys phase/phases))]
                          (let [{:keys [writes auto]} (get phase/phases p)]
                            (cond
                              (not (contains? writes o))
                              "<span class=\"critical\">HOLD (:phase-disabled)</span>"
                              (contains? auto o)
                              "<span class=\"ok\">auto-commit when clean</span>"
                              :else
                              "<span class=\"warn\">human approval</span>"))))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "apparelops.governor") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "supply-cost escalation threshold"
                    (code governor/supply-cost-threshold))
                (tr "allowed ops (closed allowlist)" (kw-codes governor/allowed-ops))
                (tr "always-human ops" (kw-codes governor/always-escalate-ops))
                (tr "permanently out-of-scope phrases scanned"
                    (str (esc (count governor/scope-excluded-terms))
                         " <span class=\"muted\">each phrased as the finalization ACTION "
                         "(&ldquo;issue the refund&rdquo;, &ldquo;declare the item "
                         "counterfeit&rdquo;), never a bare noun</span>"))])))

(defn- last-fact-for [led store-id]
  (last (filter #(= store-id (:store-id %)) led)))

(defn- store-status [led store-id]
  (let [f (last-fact-for led store-id)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">last fact: committed</span>"
      (= :approval-rejected (:t f))
      "<span class=\"critical\">last fact: rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">last fact: HARD hold</span> "
           (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- stores-section [db]
  (let [led (ledger-of db)]
    (card "Store directory"
          (str "Read back from " (code "apparelops.store/all-store-records") ". A proposal may not "
               "commit or even escalate unless the target store is BOTH registered and verified, "
               "re-derived from these fields — never from the request's own claim. "
               (code "store-99") " is driven in the timeline above precisely because it is absent "
               "here.")
          (table ["Store" "Name" "registered?" "verified?" "Ledger status"]
                 (for [s (store/all-store-records db)]
                   (tr (code (:store-id s)) (esc (:name s))
                       (flag (:registered? s)) (flag (:verified? s))
                       (store-status led (:store-id s))))))))

(defn- vendors-section [db]
  (let [orders (filter #(= :coordinate-supply-order (:op %)) (store/coordination-log db))]
    (card "Vendor directory"
          (str "Read back from " (code "apparelops.store/all-vendor-records") ". For "
               (code ":coordinate-supply-order") " only, the drafted value's "
               (code ":vendor-id") " must resolve to a registered + verified counterparty here. "
               "The last column counts committed supply orders naming that vendor, joined from "
               (code "apparelops.store/coordination-log") ".")
          (table ["Vendor" "Name" "registered?" "verified?" "Committed supply orders"]
                 (for [v (store/all-vendor-records db)]
                   (tr (code (:vendor-id v)) (esc (:name v))
                       (flag (:registered? v)) (flag (:verified? v))
                       (esc (count (filter #(= (:vendor-id v) (get-in % [:value :vendor-id]))
                                           orders)))))))))

(defn- coordination-section [db]
  (let [recs (store/coordination-log db)]
    (card "Committed coordination records"
          (str "The append-only committed-proposal history ("
               (code "apparelops.store/coordination-log")
               "). These are drafts a coordinator keeps: none of them sets a price, resolves a "
               "quality dispute or determines authenticity. The approver appears only where a "
               "human actually resumed the paused graph — "
               (code ":request-approval") " writes it onto the record's "
               (code ":payload") ".")
          (if (seq recs)
            (table ["#" "Op" "Store" "Drafted value" "Approved by"]
                   (map-indexed
                    (fn [i r]
                      (tr (esc (inc i)) (code (:op r)) (code (:store-id r))
                          (kv-pairs (dissoc (:value r) :store-id))
                          (fmt (get-in r [:payload :approved-by]))))
                    recs))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as " (code "apparelops.store/ledger")
             " returns it.")
        (table ["#" "Fact" "Op" "Store" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (esc (inc i))
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:store-id f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-4771 (apparelops)</title>"
       "<style>" (skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Clothing, footwear &amp; leather-goods retail operations — operator console</h1>"
       "</header>\n"
       "<p class=\"subtitle\"><span class=\"badge\">ISIC 4771</span> "
       "<span class=\"badge\">apparelops</span> "
       "governor <code>apparel-retail-governor</code> · actor "
       (esc (:actor-id coordinator)) " · role " (code (:actor-role coordinator))
       " · default phase " (esc (:phase coordinator))
       "</p>\n<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (stores-section db)
                          (vendors-section db)
                          (coordination-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>apparelops.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>apparelops.operation</code> actor graph over the real "
       "<code>apparelops.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "Every store, vendor, id, figure and rule name on this page is the actor's own output; no "
       "usage, revenue or performance metric is claimed anywhere."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
