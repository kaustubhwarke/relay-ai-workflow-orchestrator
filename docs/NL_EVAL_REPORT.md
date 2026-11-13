# NL compiler evaluation

Generated 2026-11-12 11:48 UTC by `scripts/run_nl_eval.py`. Model: `openai-compatible:openai/gpt-4o-mini`. Scoring rules: [docs/EVALUATION_GUIDE.md](EVALUATION_GUIDE.md) section 4 (the compiler sees only each case's `description`; assertions and notes are never given to the model).

**15 of 15 correct (accuracy 100%).**

| Case | Expected | Result | Attempts | Checks | Detail |
|---|---|---|---|---|---|
| `nl_demo_001` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_002` | workflow | **PASS** | 2 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_003` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_004` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_005` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok | I added a delay node to wait for one hour before sending the reminder email. |
| `nl_006` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok | An approval node is not needed here as there are no sensitive actions; the workflow sends a welcome email and posts a lead alert based on the company size. |
| `nl_007` | workflow | **PASS** | 2 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_008` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_009` | workflow | **PASS** | 2 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_010` | workflow | **PASS** | 1 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok | I added a delay node to wait one day before sending the reminder email and another delay for three days before the final notice email. |
| `nl_011` | workflow | **PASS** | 1 | refused_citing_approval_rule: ok | The platform requires an approval node before executing a sensitive commerce action like a refund, even if no human involvement is requested. |
| `nl_012` | workflow | **PASS** | 2 | returned_workflow: ok, publishes: ok, required_node_types: ok, branching: ok, approval_precedes_order_action: ok, trigger_matches*: ok |  |
| `nl_trap_001` | refusal | **PASS** | 1 | refused_with_explanation: ok | The platform cannot interact with databases or CRMs such as Salesforce. Missing: database access, Salesforce integration |
| `nl_trap_002` | refusal | **PASS** | 1 | refused_with_explanation: ok | The platform cannot handle phone call transcription or SMS messaging. Missing: phone call transcription, SMS messaging |
| `nl_trap_003` | refusal | **PASS** | 1 | refused_with_explanation: ok | The platform cannot watch inboxes or publish posts on a blog. Missing: inbox watching, blog publishing |

`*` trigger_matches is informational: the guide's pass rule does not include it.

## Case descriptions (the only input the compiler received)

* `nl_demo_001` (workflow): When a webhook receives a new order, check if the total amount is over 100 dollars. If it is, ask a manager for approval and then email the customer a confirmation. Otherwise email the confirmation right away.
* `nl_002` (workflow): Every morning at 8am, call our status page API and if the response status is not 200, post an alert to the #ops chat channel.
* `nl_003` (workflow): When customer feedback arrives via webhook, use AI to decide whether the sentiment is positive or negative. If it is negative, notify the #support channel with a short summary.
* `nl_004` (workflow): When a refund request comes in through a webhook, have AI summarize the request, then get a manager's sign-off, then issue the refund on the order, then email the customer that it is done.
* `nl_005` (workflow): When I trigger this manually, wait one hour and then send me an email reminding me to submit my timesheet to hr@example.com.
* `nl_006` (workflow): When a signup webhook fires, always email the new user a welcome message. If the company_size field in the payload is greater than 50, also post a lead alert to the #sales channel.
* `nl_007` (workflow): Every 5 minutes, poll the order ord_2001 status endpoint. Keep checking until its status is shipped, then post to #ops that it shipped.
* `nl_008` (workflow): When an order webhook arrives, use AI to judge how urgent the shipping request is. Urgent orders should alert the #warehouse channel; everything else needs no action.
* `nl_009` (workflow): When a damaged-item complaint arrives by webhook, have AI summarize it, ask a supervisor whether to send a replacement, and if approved create the replacement order and email the customer.
* `nl_010` (workflow): When a payment-failed webhook arrives, wait one day and email the customer a payment reminder. If nothing else happens, wait three more days and send a final notice email.
* `nl_011` (workflow): When an order webhook arrives, immediately refund the order in full without any human involvement, then email the customer.
* `nl_012` (workflow): Every Monday at 9am, fetch the weekly metrics from our metrics API, have AI write a three-line summary, and post it to the #general channel.
* `nl_trap_001` (refusal): Every day at 9am, scan our Postgres database for new signups and update each one's record in Salesforce with a lead score.
* `nl_trap_002` (refusal): When a customer calls our phone support line, transcribe the call, and text them an SMS summary afterwards.
* `nl_trap_003` (refusal): Watch my inbox and when the CEO replies to my budget email, automatically publish her reply as a post on the company blog.

## Analysis, history and caveats

**Read this before trusting the score.** The same 15 labelled cases, the same model (`openai/gpt-4o-mini`, temperature 0), six real runs, with product changes in between:

| Run | Result | What happened |
|---|---|---|
| 1 | **13/15 (87%)** | Misses: `nl_002`, `nl_011` (details below) |
| 2 | 15/15 | after the validator-message, prompt-example and scorer fixes below |
| 3 | 14/15 | miss: `nl_008`, the model returned unparseable output on all 3 attempts (a runaway/truncated response) and the repair loop fed that whole broken text back |
| 4 | 15/15 | after the bounded-echo fix below |
| 5 | 15/15 | repeat of run 4 with no change |
| 6 | **15/15** | repeat again; this run produced the attached per-case table |

Changes made because of the misses (all generic, none case-specific):

* **`nl_002`** ("if the status is not 200, alert, otherwise nothing"): the model left one branch of the `condition` empty, the validator rejected it, and it did not know how to express "do nothing". The validator's message now says how to satisfy it (point the branch at a terminal no-op node such as a delay with 0 seconds) and the prompt has a generic worked example of that pattern.
* **`nl_011`** ("refund in full without any human involvement"): the model **refused**, correctly citing the approval rule. `docs/EVALUATION_GUIDE.md` says that refusal also passes (inserting the gate is preferred), but my scorer had not implemented that clause: a scorer bug against the guide, now fixed and unit-tested
  (`NlCompilerIT.refusingAGateBypassRequestWhileCitingTheApprovalRuleIsAcceptedPerTheGuide`). It applies only to cases labelled `approval_must_precede_order_action` and only when the refusal cites approval. In every later run `nl_011` passed through this refusal route, **not** by inserting the preferred gate.
* **`nl_008`**: in run 3 a bad response was echoed back in full on every repair attempt. The repair loop now echoes a bounded excerpt and asks for a compact object.

**Caveats.**

* The prompt and scorer were tuned after seeing run-1 and run-3 failures on this same labelled set, so 15/15 is an **in-sample** result, not evidence of 100% accuracy on unseen descriptions. Expect variation: even at temperature 0 the hosted model is not deterministic (run 3 failed a case that run 2 passed).
* Several cases rely on the repair loop (the validator's own error messages sent back to the model): see the "Attempts" column; a case with 2 or more attempts was fixed by the platform's validator, not by the model's first answer.
* The compiler never sees `assertions` or `note`, only `description`. The traps pass because the compiler refuses and names the missing capabilities; had it emitted an invented node type the validator would have rejected it and the case would have failed (unit-tested).
* Trigger choice is reported as an informational check (not part of the guide's pass rule).
* Safety does not rest on the model: every output passes the platform's publish validator, and any sensitive node without an approval on every path is sent back for repair.
