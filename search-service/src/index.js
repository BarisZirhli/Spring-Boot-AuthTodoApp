require('dotenv').config();
const express = require('express');
const { Pool } = require('pg');
const jwt = require('jsonwebtoken');

const app = express();
app.use(express.json());

// Database connection pool
const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '5432'),
  database: process.env.DB_NAME || 'tododb',
  user: process.env.DB_USERNAME || 'postgres',
  password: process.env.DB_PASSWORD || 'postgres',
});


function authenticate(req, res, next) {
  const authHeader = req.headers['authorization'];
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Authorization header missing' });
  }

  const token = authHeader.split(' ')[1];
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET);
    req.username = decoded.sub;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}

// GET /api/search/todos?q=keyword&completed=true&page=1&size=10
app.get('/api/search/todos', authenticate, async (req, res) => {
  const { q, completed, page = 1, size = 10 } = req.query;

  const offset = (parseInt(page) - 1) * parseInt(size);
  const limit = parseInt(size);
  const username = req.username;

  const conditions = ['owner_username = $1', 'deleted_at IS NULL'];
  const params = [username];
  let paramIndex = 2;

  if (q) {
    conditions.push(`(title ILIKE $${paramIndex} OR description ILIKE $${paramIndex})`);
    params.push(`%${q}%`);
    paramIndex++;
  }

  if (completed !== undefined) {
    conditions.push(`completed = $${paramIndex}`);
    params.push(completed === 'true');
    paramIndex++;
  }

  const whereClause = conditions.join(' AND ');

  try {
    const countResult = await pool.query(
      `SELECT COUNT(*) FROM todos WHERE ${whereClause}`,
      params
    );
    const total = parseInt(countResult.rows[0].count);

    const dataResult = await pool.query(
      `SELECT id, title, description, completed, created_at, updated_at
       FROM todos
       WHERE ${whereClause}
       ORDER BY created_at DESC
       LIMIT $${paramIndex} OFFSET $${paramIndex + 1}`,
      [...params, limit, offset]
    );

    res.json({
      data: dataResult.rows,
      pagination: {
        total,
        page: parseInt(page),
        size: limit,
        totalPages: Math.ceil(total / limit),
      },
    });
  } catch (err) {
    console.error('Search error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// Health check
app.get('/api/search/health', (req, res) => {
  res.json({ status: 'UP', service: 'search-service' });
});

const PORT = process.env.SERVER_PORT || 8084;
app.listen(PORT, () => {
  console.log(`Search service running on port ${PORT}`);
});