Coding rules for the twinme-android-common:

## 1. General Principles

- Read the top-level `CODING_GUIDELINES.md` and follow the `General Principles`.
- The module must only contain packages for `org.twinlife.twinme` and its children.

## 2. Services

Services in `org.twinlife.twinme.services` package should follow the following rules:

- follow the section `4. State machines` in the top-level `CODING_GUIDELINES.md`
- the service classes should inherit from `AbstractTwinmeService`,
- the service observer methods must be executed from the main UI thread
  (the `runOnUiThread()` guard should be used),
- a service that uses a twinlife service or a twinme service through the `TwinmeContext`
  interface should define the observer or callback method with a name prefixed by `on`
  and the method must be `private` or `protected`. Example:
  ```java
   public class ShowContactService extends AbstractTwinmeService {
    ...
    protected void onOperation() {
       ...
       mTwinmeContext.getContact(mContactId, this::onGetContact);
       ...
    }
    private void onGetContact(@NonNull ErrorCode errorCode, @Nullable Contact contact) {
       ...
    }
  }
  ```
- the service public methods are executed from the main UI thread, but the service `onOperation()`
  must be executed from the twinlife executor's thread,
- a service public method executed from the main UI thread, should call `startOperation()` to
  trigger the execution of `onOperation()`.
- the `BackupService`, `PeerService` and `AccountMigrationService` defined in the package
  `org.twinlife.twinme.services` don't follow these rules: they are Android services
  (should be moved to another package), this is an exception that must be avoided.
- the `AdminService` is an exception and does not need to inherit from the `AbstractTwinmeService`.
- the `dispose()` operation of the service must call the super method when it is defined.

