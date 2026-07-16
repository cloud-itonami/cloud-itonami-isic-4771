(ns apparelops.advisor
  "ApparelRetailAdvisor -- the *contained intelligence node* for the
  ISIC-4771 'Retail sale of clothing, footwear and leather articles in
  specialized stores' operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: inventory/sale/return/alteration transaction logging,
  floor-staff/fitting-room scheduling, apparel/footwear/leather-goods
  supply-order coordination, and quality-concern flagging (a defective
  garment, a mis-sized/mislabeled item, or a suspected-counterfeit item).
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record and
  NEVER a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by `apparelops.governor`
  before anything touches the SSoT.

  This advisor NEVER drafts a shelf/unit-price decision, a direct
  quality-dispute-resolution-finalization action (issuing a refund or
  replacement, voiding a sale, charging back a vendor, revoking or
  terminating a vendor's registration/contract, or otherwise declaring a
  quality dispute resolved), a direct counterfeit-authenticity-
  determination action (declaring an item counterfeit, certifying an
  item as genuine, or otherwise finalizing an authenticity
  determination), or any other quality-dispute-resolution or
  counterfeit-authenticity-resolution authority action -- those are
  permanently out of scope for this actor, not merely un-implemented.
  `apparelops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised or
  confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :store-id   str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-sales-record
  "Draft an inventory/sale/return/alteration transaction log entry. Pure
  logging of observed transactions (units sold, returns processed,
  tailoring-alteration requests logged, stock-count deltas) -- never a
  shelf/unit-price decision."
  [_db {:keys [store-id patch]}]
  {:op         :log-sales-record
   :store-id   store-id
   :summary    (str store-id " の販売/在庫/返品/直し(お直し)記録を記録: " (pr-str (keys patch)))
   :rationale  "販売数量・返品件数・お直し(裾上げ等)依頼件数・在庫カウントの観察記録のみ。値付けや品質紛争・真贋判定は含まない。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.93})

(defn- propose-staffing-operation
  "Draft a floor-staff/fitting-room scheduling proposal (a roster/
  calendar entry, never a direct enforcement action)."
  [_db {:keys [store-id patch]}]
  {:op         :schedule-staffing-operation
   :store-id   store-id
   :summary    (str store-id " のフロアスタッフ/試着室配置予定を提案: " (pr-str (keys patch)))
   :rationale  "フロア/レジ/試着室・フィッティング対応の人員配置調整提案のみ。最終配置は人間が確定する。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an apparel/footwear/leather-goods procurement coordination
  request naming a registered vendor (manufacturer/wholesaler/
  distributor) -- never a finalized purchase order; a human always
  confirms procurement."
  [_db {:keys [store-id patch]}]
  {:op         :coordinate-supply-order
   :store-id   store-id
   :summary    (str store-id " 向け衣料/靴/革製品の在庫調達を提案: " (pr-str (keys patch)))
   :rationale  "衣料品・靴・革製品(バッグ/ベルト等)の在庫調達コーディネート提案のみ。確定発注は人間が行う。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence 0.90})

(defn- propose-quality-concern
  "Surface an observed quality concern (a defective garment, a mis-sized/
  mislabeled item, or a suspected-counterfeit item) for HUMAN triage.
  This op ALWAYS escalates in `apparelops.governor` -- never
  auto-committed at any phase -- regardless of how confident the advisor
  is that the concern is real. Deliberately reports the OBSERVATION
  only, never a finalization/resolution/determination action, so the
  default rationale never trips the governor's `scope-excluded-terms`
  (see that var's docstring)."
  [_db {:keys [store-id patch]}]
  {:op         :flag-quality-concern
   :store-id   store-id
   :summary    (str store-id " の品質懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "縫製不良・サイズ/繊維表示の誤り・真正性への疑念(偽造品の疑いを含む)等の観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [store-id]
   :effect     :propose
   :value      (merge {:store-id store-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-sales-record (propose-sales-record _db request)
                   :schedule-staffing-operation (propose-staffing-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-quality-concern (propose-quality-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually issued the refund and declared the item counterfeit to resolve the dispute")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :store-id (:store-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
