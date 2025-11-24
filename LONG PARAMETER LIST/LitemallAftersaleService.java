package org.linlinjava.litemall.db.service;

import org.linlinjava.litemall.db.dao.LitemallAftersaleMapper;      // LitemallAftersaleMapper: MyBatis mapper for CRUD on aftersale table
import org.linlinjava.litemall.db.domain.LitemallAftersale;        // LitemallAftersale: domain model representing an aftersale record
import org.linlinjava.litemall.db.domain.LitemallAftersaleExample; // LitemallAftersaleExample: MyBatis example/criteria builder for queries
import org.linlinjava.litemall.db.domain.LitemallAftersale.Column; // Column: type-safe column constants (e.g. addTime.desc()) for ORDER BY

import com.github.pagehelper.PageHelper;                           // PageHelper: plugin that intercepts MyBatis queries to apply pagination

import org.springframework.stereotype.Service;                     // @Service: marks this class as a Spring service bean
import org.springframework.util.StringUtils;                       // StringUtils: helper for null/empty checks on sort and order fields

import javax.annotation.Resource;                                  // @Resource: JSR-250 injection annotation for wiring the mapper bean

import java.time.LocalDate;                                        // LocalDate: date without time used for simple date values (if needed)
import java.time.LocalDateTime;                                    // LocalDateTime: date-time used when generating time-based codes
import java.time.format.DateTimeFormatter;                         // DateTimeFormatter: formats dates into the yyyyMMdd pattern
import java.util.List;                                             // List: collection type for returning pages of aftersale records
import java.util.Random;                                           // Random: generates numeric suffix for aftersaleSn codes

/**
 * Service responsible for user aftersale (post-sale) operations.
 *
 * It encapsulates access to {@link LitemallAftersaleMapper} and centralizes
 * logic for:
 *  - generating aftersale service codes,
 *  - building query filters,
 *  - applying soft delete rules (logical deletion),
 *  - enforcing user ownership constraints.
 */
@Service
public class LitemallAftersaleService {

    /**
     * MyBatis mapper that performs CRUD operations on the aftersale table.
     */
    @Resource
    private LitemallAftersaleMapper aftersaleMapper;

    /**
     * Find an aftersale record by primary key.
     * <p>
     * This variant does not validate the user; it is typically used
     * in internal/admin flows where user ownership is managed elsewhere.
     */
    public LitemallAftersale findById(Integer id) {
        return aftersaleMapper.selectByPrimaryKey(id);
    }

    /**
     * Find an aftersale record by id, enforcing:
     * <ul>
     *   <li>the record belongs to the given {@code userId};</li>
     *   <li>the record is not logically deleted.</li>
     * </ul>
     * This is the safe variant for user-facing flows.
     */
    public LitemallAftersale findById(Integer userId, Integer id) {
        LitemallAftersaleExample example = new LitemallAftersaleExample();
        example.or()
                .andIdEqualTo(id)
                .andUserIdEqualTo(userId)
                .andDeletedEqualTo(false);
        return aftersaleMapper.selectOneByExample(example);
    }

    /**
     * Paged query of a user's aftersale requests.
     * <p>
     * Supports:
     * <ul>
     *   <li>optional filter by status (e.g. applied, approved, rejected);</li>
     *   <li>soft-delete filtering (only non-deleted records);</li>
     *   <li>dynamic sorting ({@code sort} + {@code order}) or default
     *       sorting by {@code addTime} if not provided.</li>
     * </ul>
     */
    public List<LitemallAftersale> queryList(Integer userId,
                                             Short status,
                                             Integer page,
                                             Integer limit,
                                             String sort,
                                             String order) {

        LitemallAftersaleExample example = new LitemallAftersaleExample();
        LitemallAftersaleExample.Criteria criteria = example.or();

        // Always filter by user that owns the aftersale record
        criteria.andUserIdEqualTo(userId);

        // Optional filter by business status of the aftersale
        if (status != null) {
            criteria.andStatusEqualTo(status);
        }

        // Ignore logically deleted entries
        criteria.andDeletedEqualTo(false);

        // Apply dynamic sorting if both sort field and order are provided
        if (!StringUtils.isEmpty(sort) && !StringUtils.isEmpty(order)) {
            example.setOrderByClause(sort + " " + order);
        } else {
            // Fallback: sort by creation date (most recent first)
            example.setOrderByClause(Column.addTime.desc());
        }

        // Integrates MyBatis with PageHelper to apply pagination
        PageHelper.startPage(page, limit);
        return aftersaleMapper.selectByExample(example);
    }

    /**
     * Generates a random numeric suffix with the given number of digits.
     * <p>
     * Used as the random part of the {@code aftersaleSn} code.
     */
    private String getRandomNum(Integer num) {
        String base = "0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();

        for (int i = 0; i < num; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }

        return sb.toString();
    }

    /**
     * Counts how many non-deleted aftersale records a user has
     * with the given {@code aftersaleSn}.
     * <p>
     * This is used to ensure practical uniqueness of an aftersale code
     * per user.
     */
    public int countByAftersaleSn(Integer userId, String aftersaleSn) {
        LitemallAftersaleExample example = new LitemallAftersaleExample();
        example.or()
                .andUserIdEqualTo(userId)
                .andAftersaleSnEqualTo(aftersaleSn)
                .andDeletedEqualTo(false);
        return (int) aftersaleMapper.countByExample(example);
    }

    /**
     * Generates a unique aftersale code ({@code aftersaleSn}) for a user.
     * <p>
     * Format:
     * <pre>
     *   yyyyMMdd + 6-digit random numeric suffix
     * </pre>
     * If a collision is detected for the given user,*
