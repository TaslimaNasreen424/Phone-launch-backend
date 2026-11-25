package com.bell.phone.launch.repository;

import com.bell.phone.launch.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Query("SELECT r FROM Reservation r WHERE r.customerId = ?1 AND r.status = 'ACTIVE'")
    List<Reservation> findActiveByCustomerId(String customerId);

    @Query("SELECT COUNT(r) FROM Reservation r WHERE r.customerId = ?1 AND r.status = 'ACTIVE' AND r.expiresAt > CURRENT_TIMESTAMP")
    Long countActiveReservations(String customerId);
}
