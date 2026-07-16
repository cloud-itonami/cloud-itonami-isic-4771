(ns apparelops.governor-test
  "Pure unit tests of `apparelops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [apparelops.advisor :as adv]
            [apparelops.governor :as gov]
            [apparelops.store :as store]))

(def store-1 {:store-id "store-1" :name "Meridian Menswear & Footwear" :registered? true :verified? true})
(def store-3 {:store-id "store-3" :name "Pop-Up Sneaker Concept Store" :registered? true :verified? false})
(def vendor-1 {:vendor-id "vendor-1" :name "Cascade Footwear Manufacturing Co." :registered? true :verified? true})
(def vendor-2 {:vendor-id "vendor-2" :name "Unverified Import Apparel Broker Co." :registered? true :verified? false})

(defn- clean-proposal [op store-id]
  {:op op :store-id store-id :summary "s" :rationale "routine apparel store coordination"
   :cites [store-id] :effect :propose :value {} :confidence 0.85})

(defn- clean-supply-order [store-id vendor-id cost]
  (assoc (clean-proposal :coordinate-supply-order store-id)
         :value {:store-id store-id :vendor-id vendor-id :estimated-cost cost}))

(deftest store-unregistered-is-hard
  (testing "no store record at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "unknown-store") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest store-unverified-is-hard
  (testing "store registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"store-3" store-3})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "store-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-missing-on-supply-order-is-hard
  (testing "supply-order proposal with no :vendor-id at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" nil 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-unregistered-on-supply-order-is-hard
  (testing "supply-order proposal naming an unknown vendor -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" "unknown-vendor" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-unverified-on-supply-order-is-hard
  (testing "supply-order proposal naming a registered-but-unverified vendor -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1 "vendor-2" vendor-2})
          verdict (gov/check {} nil (clean-supply-order "store-1" "vendor-2" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:vendor-unverified} (map :rule (:violations verdict)))))))

(deftest vendor-verified-on-supply-order-is-not-hard-on-vendor-check
  (testing "supply-order proposal naming a verified vendor never trips :vendor-unverified"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          verdict (gov/check {} nil (clean-supply-order "store-1" "vendor-1" 100.0) s)]
      (is (empty? (filter #(= :vendor-unverified (:rule %)) (:violations verdict)))))))

(deftest vendor-check-is-scoped-to-supply-order-only
  (testing "non-supply-order ops never trip :vendor-unverified, even with no vendors registered at all"
    (let [s (store/mem-store {"store-1" store-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :flag-quality-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "store-1") s)]
          (is (empty? (filter #(= :vendor-unverified (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :vendor-unverified")))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "store-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :finalize-quality-dispute "store-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest refund-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly issuing a refund is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "issued the refund to the customer and confirmed the defective garment claim"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest void-sale-content-is-hard
  (testing "a proposal touching voiding the sale is HARD-blocked, same as refund issuance"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "voided the sale before the customer left the store"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest chargeback-content-is-hard
  (testing "a proposal touching charging back the vendor is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          poisoned (assoc (clean-supply-order "store-1" "vendor-1" 100.0)
                          :summary "charged back the vendor for the defective shipment")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest vendor-registration-revocation-content-is-hard
  (testing "a proposal touching revoking the vendor's registration is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          poisoned (assoc (clean-supply-order "store-1" "vendor-1" 100.0)
                          :summary "revoked the vendor's registration after the mislabeling incident")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest counterfeit-declaration-content-is-hard
  (testing "a proposal touching directly declaring an item counterfeit is HARD-blocked -- counterfeit-authenticity determination is permanently out of scope"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "declared the item counterfeit after comparing it to the branded original"
                          :confidence 0.95)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest genuine-certification-content-is-hard
  (testing "a proposal touching certifying an item as genuine is HARD-blocked, same as declaring it counterfeit"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "certified the item as genuine and closed the authenticity claim"
                          :confidence 0.95)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-quality-concern-is-not-scope-excluded
  (testing "flagging an observed defective-garment/mis-sized/mislabeled/suspected-counterfeit concern (not a resolution finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          concern (assoc (clean-proposal :flag-quality-concern "store-1")
                         :value {:concern "jacket #4771 has a torn lining, leather-content label may be wrong, and the item may be a suspected-counterfeit of the branded original"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (defective garment/mislabeling/suspected-counterfeit) is exactly what this op exists to surface"))))

(deftest quality-concern-always-escalates-clean
  (testing ":flag-quality-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-quality-concern "store-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          expensive (assoc (clean-supply-order "store-1" "vendor-1" 5000.0) :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})
          cheap (assoc (clean-supply-order "store-1" "vendor-1" 480.0) :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "refund", "dispute" or "counterfeit"), which then accidentally matches
;; inside the mock advisor's own DEFAULT rationale/disclaimer text for a
;; legitimate, allowed proposal -- causing the actor to self-block its
;; own happy path. This is a dedicated regression test: every op the
;; default mock advisor can generate, with default (non-`out-of-scope?`)
;; request patches, must NEVER trip `:scope-excluded` or
;; `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"store-1" store-1} {"vendor-1" vendor-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                  :flag-quality-concern]]
        (let [patch (if (= op :coordinate-supply-order)
                      {:item "seasonal footwear restock" :estimated-cost 480.0 :vendor-id "vendor-1"}
                      {})
              proposal (adv/infer nil {:op op :store-id "store-1" :patch patch})
              verdict (gov/check {:store-id "store-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
