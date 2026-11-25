# Test Plan and Results

## Execution summary

| Item | Result |
| --- | --- |
| Run date | 25 November 2026 |
| Command | `mvn test` |
| Test framework | JUnit 5, Spring Boot Test, Mockito, MockMvc, and H2 |
| Test suites | 2 |
| Tests reported | 21 |
| Passed | 20 |
| Failed | 0 |
| Errors | 0 |
| Skipped | 1 |
| Build result | `BUILD SUCCESS` |

These are unit and application integration tests using an in-memory H2
database. They do not measure launch-day capacity, production database
behavior, or performance under load.

## Results by suite

| Suite | Tests | Passed | Failed | Errors | Skipped | Elapsed |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `ReservationServiceTest` | 11 | 11 | 0 | 0 | 0 | 0.947 s |
| `ReservationIntegrationTest` | 10 | 9 | 0 | 0 | 1 | 20.166 s |
| **Total** | **21** | **20** | **0** | **0** | **1** | **21.113 s** |

## Coverage and observed outcomes

### Service unit tests

The unit suite verifies the following service behaviors:

- A reservation moves the requested quantity from available to reserved,
  records an active reservation, and sets an expiry about 15 minutes later.
- Unknown products, insufficient stock, and a customer's fifth existing
  active unexpired reservation are rejected without saving or changing stock.
- Reservations belonging to other customers, completed reservations, and
  expired reservations do not count toward the active-reservation limit.
- A valid purchase moves units from reserved to sold and marks the reservation
  completed.
- Expired reservations and reservations owned by another customer cannot be
  purchased; these rejections do not change inventory or create a purchase.
- A completed reservation cannot be purchased again.
- Explicit expiry cleanup releases only expired active holds.
- Inventory status includes available stock and calculates sold percentage
  from sold units.

### Application integration tests

The integration suite exercises API requests and database state using MockMvc
and H2:

- Reserve and purchase persist consistent reservation, purchase, and inventory
  state; a repeat purchase is rejected without creating a second purchase.
- Invalid quantities, missing fields, and an invalid purchase ID return HTTP
  400 without creating a reservation.
- Insufficient stock returns HTTP 409 without changing the available count.
- A sixth active reservation for a customer is rejected; the maximum quantity
  of five can be reserved.
- A purchase attempt using a different customer ID is rejected and leaves the
  reservation active, preserves reserved inventory, and creates no purchase.
- An expired reservation purchase returns HTTP 410; invoking the cleanup method
  releases its units.
- A simulated inventory-save failure rolls back the reservation insert.
- A stale inventory version cannot overwrite a concurrently committed update,
  verifying optimistic locking.

## Skipped test and known behavior

`ReservationIntegrationTest.purchaseHistoryStoresUnitPrice` is skipped because
purchase completion currently does not populate `PURCHASE_HISTORY.UNIT_PRICE`.
The test expects the stored unit price to be `1099.99`; that behavior remains
unverified until the defect is fixed and the test is enabled.

The integration tests also record current API behavior rather than asserting an
idealized error contract: an owner mismatch and a repeat purchase currently
return HTTP 500. The owner-mismatch test verifies that the transaction does not
complete the reservation, change reserved stock, or create a purchase. These
tests do not establish that HTTP 500 is the desired client-facing status.

## Reproduce

From the project root, run:

```powershell
mvn test
```

Surefire writes per-suite results under `target/surefire-reports/`.
