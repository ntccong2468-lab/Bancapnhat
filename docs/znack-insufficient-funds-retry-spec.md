# Spec: Znack insufficient-funds recovery

## Objective

When Znack rejects a KIZ order with error `3590` / `NotEnoughMoneyException`, WCode must explain that
the Znack balance is insufficient and let the user retry the same persisted purchase pipeline after
funding the account. The retry must not create a parallel pipeline or automatically hammer Znack.

## Commands

- Targeted tests: `./mvnw -B -Dtest=ZnackModuleTest,ZnackGtinWorkflowTest test`
- Full verification: `./mvnw -B test`

## Project structure

- `src/main/java/.../integration/znack`: error classification, persisted pipeline and safe retry
- `src/main/java/.../ui/znack`: JavaFX insufficient-funds dialog coordination
- `src/main/java/.../ui/{supply,ozon,kizmapping}`: surfaces that observe KIZ pipeline summaries
- `src/main/resources/.../i18n`: localized dialog copy
- `src/test/java/...`: unit and workflow regression tests

## Code style

Use the existing JavaFX `Task`/`AppTaskExecutor` pattern for background work and the existing
`AlertService` theme. Error detection is centralized in `ZnackErrorMessages`; UI controllers must
not parse raw API strings independently.

## Testing strategy

- Unit-test error `3590` and `NotEnoughMoneyException` classification, including false positives.
- Workflow-test that retry reuses the original pipeline ID and becomes purchasable after funding.
- Run the complete Maven test suite, including JavaFX FXML smoke tests.

## Boundaries

- Always: retain the raw sanitized error for audit; execute retry off the JavaFX thread.
- Ask first: database schema changes or new dependencies (neither is required).
- Never: automatically retry insufficient funds on a timer; create a second purchase pipeline from
  the dialog; expose account/token secrets.

## Success criteria

- Error `3590` displays a localized insufficient-balance dialog once per failed pipeline.
- The dialog has “Retry” and “Close” actions.
- “Retry” requeues the exact failed pipeline through the existing FIFO coordinator.
- Closing the dialog leaves the pipeline untouched.
- A still-insufficient retry may notify again, while refresh timers do not spam dialogs.
- Unrelated errors continue using the existing error UI.

## Open questions

None. The request and existing persisted-pipeline architecture define the behavior.
