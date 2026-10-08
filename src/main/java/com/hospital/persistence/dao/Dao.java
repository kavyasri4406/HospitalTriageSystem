package com.hospital.persistence.dao;

import java.util.List;
import java.util.Optional;

/**
 * Generic Data Access Object contract.
 *
 * @param <T>  entity type
 * @param <ID> primary key type
 */
public interface Dao<T, ID> {

    List<T> findAll();

    Optional<T> findById(ID id);

    /** Inserts the entity; for generated keys the id is written back into the entity. */
    void insert(T entity);

    void update(T entity);
}
