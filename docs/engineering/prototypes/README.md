# Credential migration: isolated model

`credential_migration_model_test.py` is a throwaway synthetic SQLite model to explore backup-before-write, transactional changes, interruption, retry, concurrent changes, identity preservation, historical pipeline data, duplicate credentials, and restoration. Run with:

```sh
python3 docs/engineering/prototypes/credential_migration_model_test.py
```

It is not used by VN Code, is not a migration, and must not be used with real data. Its deliberately insecure, reversible fake codec is neither DPAPI nor encryption; its tables and fingerprint algorithm are simplified and do not represent production. The model does not verify Java repositories, actual consumer logic, SQLite WAL recovery, Windows user profiles, DPAPI, process interruption, logging, or the application startup gate. Passing it does not demonstrate that a migration is safe for release. Production integration requires separate Java integration tests, Windows tests with synthetic accounts and DPAPI, and complete adaptation of all consumers identified in `../credential-migration-proposal.md`.
