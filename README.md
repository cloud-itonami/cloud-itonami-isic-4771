# cloud-itonami-isic-4771

Open Business Blueprint for **ISIC Rev.5 4771**: retail sale of clothing,
footwear and leather articles in specialized stores -- apparel/footwear/
leather-goods storefronts selling finished garments, shoes, and leather
articles (bags, belts, small leather goods), distinct from ISIC 4751
textile/fabric retail, which sells bolts of yardage/notions, not
finished goods.

This repository publishes a clothing/footwear/leather-retail
operations-COORDINATION actor -- inventory/sale/return/alteration
transaction logging, floor-staff/fitting-room scheduling, apparel/
footwear/leather-goods supply-order coordination with registered
vendors, and quality-concern flagging -- as an OSS business that any
qualified operator can fork, deploy, run, improve and sell, so an
independent apparel/footwear/leather store never surrenders its
operations data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **ApparelRetailAdvisor ⊣
ApparelRetailGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:apparel-retail-governor`, is a
distinct, independent build (no naming-collision precedent question --
distinct from sibling 47xx actors' own governor keywords, e.g. ISIC
4751's `:textile-retail-governor` and ISIC 4719's
`:merchandise-retail-governor`).

> **Why an actor layer at all?** An LLM is great at drafting a sales-
> record summary, a staffing proposal, or a supply-order request -- but
> it has no license to actually issue a refund, void a sale, chargeback
> a vendor to resolve a quality dispute, or declare a suspected item
> counterfeit/genuine to resolve an authenticity claim, no way to
> independently confirm a store or a supply-order vendor is actually a
> registered/verified counterparty, and no notion of when a "flag this
> concern" op quietly turns into a claim to have already resolved it.
> Letting it act directly invites an unverified store's data entering the
> ledger, an unverified vendor receiving an apparel order, or -- worst of
> all -- a fabricated claim to have already refunded a customer or
> declared a disputed item counterfeit/genuine, exposing the shop to real
> liability. This project seals the ApparelRetailAdvisor into a single
> node and wraps it with an independent **ApparelRetailGovernor**, a
> human **approval workflow**, and an immutable **audit ledger**.

## Scope: coordination only, not dispute/authenticity resolution

This actor is **operations coordination only**. It never performs or
authorizes:

- setting or overriding a shelf/unit price
- directly finalizing a quality-dispute resolution (issuing a refund or
  replacement, voiding a sale, charging back a vendor, revoking or
  terminating a vendor's registration/contract, or otherwise declaring a
  quality dispute resolved)
- directly finalizing a counterfeit-authenticity determination (declaring
  an item counterfeit, certifying an item as genuine, or otherwise
  resolving an authenticity claim)
- quality-dispute-resolution or counterfeit-authenticity-resolution
  authority (accepting/denying liability on the store's behalf,
  instructing a vendor's account be closed)

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a quality/
authenticity concern for a human to triage is exactly this actor's job --
`:flag-quality-concern` is never excluded by this check, only
FINALIZING/resolving/determining/actuating on that concern is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`apparelops.governor`'s `effect-not-propose-violations` HARD check and
`apparelops.phase`'s phase table, which never puts
`:flag-quality-concern` in any phase's `:auto` set). A human store
operator/apparel-quality coordinator is always the one who actually acts
on a flagged concern or confirms a high-cost supply order.

## The core contract

```
store/vendor registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ ApparelRetail-         │ ─────────────▶ │ ApparelRetailGovernor       │  (independent system)
   │ Advisor (sealed)       │  + citations    │ store-unverified ·          │
   └───────────────────────┘                 │ vendor-unverified ·         │
          │                 commit ◀┼ effect-not-propose ·               │
          │                         │ scope-excluded (quality-dispute-    │
    record + ledger        escalate ┼ resolution / counterfeit-           │
          │              (ALWAYS for│ authenticity-determination          │
          │       :flag-quality-    │ finalization) ·                    │
          │       concern/high-cost │ op-not-allowed                      │
          │       supply-order)     └────────────────────────────┘
          ▼
      human approval
```

**The ApparelRetailAdvisor never commits a proposal the
ApparelRetailGovernor would reject, and a quality-concern flag or a
high-cost supply order never commits without a human sign-off.** Hard
violations (an unregistered/unverified store; an unregistered/unverified
supply-order vendor; a non-`:propose` effect; content touching
quality-dispute-resolution or counterfeit-authenticity-determination
finalization; an op outside the closed allowlist) force **hold** and
*cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: garment/footwear shelfing,
fitting-room support, restocking, point-of-sale handling) under
human/robot floor operations gated by store policy. This actor itself
does not dispatch robot/hardware actions -- it is strictly the
operations-coordination layer (sales-record logging, staffing
scheduling, supply-order coordination, quality-concern flagging) any
physical-dispatch layer could eventually feed proposals into, always
gated the same way by the independent ApparelRetailGovernor.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`,
  `schedule-staffing-operation`, `coordinate-supply-order`,
  `flag-quality-concern` (all `:effect :propose`).
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** -- the target store's business registration
     must exist AND be independently registered/verified in the store.
  2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
     named vendor (apparel/footwear/leather-goods manufacturer/
     wholesaler/distributor) must exist AND be independently registered/
     verified -- a supply-chain counterparty-verification gate shared
     with sibling 47xx retail actors.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a quality-dispute
     resolution (refund/replacement issuance, sale voiding, vendor
     chargeback, vendor registration/contract revocation) OR a
     counterfeit-authenticity determination (declaring an item
     counterfeit, certifying an item as genuine), and an op outside the
     closed allowlist, are all permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-quality-concern` -- ALWAYS escalates, regardless of confidence
    or phase. A "flag a concern" op (defective garment, mis-sized/
    mislabeled item, suspected counterfeit) is never auto-commit
    eligible and never finalizes a quality-dispute or authenticity
    decision itself -- it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (quality concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run
```

### Test suite

- `test/apparelops/governor_test.cljk` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/apparelops/advisor_test.cljk` -- advisor proposal shape and
  consistency
- `test/apparelops/phase_test.cljk` -- rollout phase logic
- `test/apparelops/governor_contract_test.cljk` -- full graph
  integration, audit trail
- `test/apparelops/store_contract_test.cljk` -- Store protocol and
  MemStore implementation

### Modules

- `apparelops.store` -- SSoT (MemStore, String-keyed store/vendor
  directories, append-only ledger)
- `apparelops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `apparelops.governor` -- independent compliance layer
- `apparelops.phase` -- staged rollout (0→3)
- `apparelops.operation` -- langgraph-clj StateGraph
- `apparelops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4771`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Inventory/sale/return/alteration transaction logging (`:log-sales-record`) | Real POS/inventory-system integration |
| Floor-staff/fitting-room scheduling coordination (`:schedule-staffing-operation`) | Direct staff time-clock/payroll integration |
| Apparel/footwear/leather-goods supply-order coordination with a registered, verified vendor, HARD-gated on vendor verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Quality-concern flagging (defective garment, mis-sized/mislabeled item, suspected-counterfeit item), ALWAYS human-gated (`:flag-quality-concern`) | Directly finalizing any quality-dispute resolution or counterfeit-authenticity determination -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Daily reconciliation/cash-up -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a
return-authorization-intake or a shrinkage-observation check) as its own
governed op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's flagship checks already
establish.

## Maturity

`:implemented` -- `ApparelRetailAdvisor` + `ApparelRetailGovernor` run as
real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own
supply-chain vendor-verification check plus a dedicated counterfeit-
authenticity-determination scope-exclusion.

## License

Code and implementation templates are AGPL-3.0-or-later.
