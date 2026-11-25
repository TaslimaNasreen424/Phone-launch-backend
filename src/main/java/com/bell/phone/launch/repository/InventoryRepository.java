package com.bell.phone.launch.repository;

import com.bell.phone.launch.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    @Query("SELECT i FROM Inventory i WHERE i.productId = ?1")
    Optional<Inventory> findByProductId(Long productId);
}
