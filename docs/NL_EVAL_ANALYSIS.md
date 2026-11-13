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
