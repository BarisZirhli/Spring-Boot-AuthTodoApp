require('dotenv').config();
require('./tracing');
const express = require('express');
const { Client } = require('@elastic/elasticsearch');
const { Kafka } = require('kafkajs');
const { Pool } = require('pg');
const jwt = require('jsonwebtoken');

const app = express();
app.use(express.json());

// Database connection pool (source of truth for todo data)
const pool = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '5432'),
  database: process.env.DB_NAME || 'tododb',
  user: process.env.DB_USERNAME || 'postgres',
  password: process.env.DB_PASSWORD || 'postgres',
});

const ELASTICSEARCH_URL = process.env.ELASTICSEARCH_URL || 'http://localhost:9200';
const ELASTICSEARCH_INDEX = process.env.ELASTICSEARCH_INDEX || 'todos';
const SEARCH_SYNC_ON_STARTUP = (process.env.SEARCH_SYNC_ON_STARTUP || 'false') === 'true';
const SEARCH_SYNC_MODE = (process.env.SEARCH_SYNC_MODE || 'full').toLowerCase(); // full | incremental
const SEARCH_DB_SYNC_BATCH_SIZE = parseInt(process.env.SEARCH_DB_SYNC_BATCH_SIZE || '500');
const KAFKA_ENABLED = (process.env.SEARCH_KAFKA_ENABLED || 'true') === 'true';
const KAFKA_BOOTSTRAP_SERVERS = process.env.KAFKA_BOOTSTRAP_SERVERS || 'localhost:9092';
const TODO_EVENTS_TOPIC = process.env.TODO_EVENTS_TOPIC || 'todo.events';
const KAFKA_GROUP_ID = process.env.SEARCH_KAFKA_GROUP_ID || 'search-service';

const es = new Client({
  node: ELASTICSEARCH_URL,
});

let kafkaConsumer = null;
let kafkaConnected = false;

function toIsoString(value) {
  if (!value) {
    return null;
  }
  if (value instanceof Date) {
    return value.toISOString();
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function rowToTodoDocument(row) {
  return {
    id: Number(row.id),
    title: row.title,
    description: row.description ?? null,
    completed: Boolean(row.completed),
    owner_username: row.owner_username,
    created_at: toIsoString(row.created_at),
    updated_at: toIsoString(row.updated_at),
  };
}

function unwrapEsResponse(response) {
  if (response && typeof response === 'object' && 'body' in response) {
    return response.body;
  }
  return response;
}

async function ensureIndexExists() {
  const existsResponse = await es.indices.exists({ index: ELASTICSEARCH_INDEX });
  const existsBody = unwrapEsResponse(existsResponse);
  const exists = typeof existsBody === 'boolean' ? existsBody : Boolean(existsBody);
  if (exists) {
    return;
  }

  await es.indices.create({
    index: ELASTICSEARCH_INDEX,
    settings: {
      analysis: {
        analyzer: {
          tr_analyzer: {
            type: 'turkish',
          },
        },
      },
    },
    mappings: {
      properties: {
        id: { type: 'long' },
        owner_username: { type: 'keyword' },
        completed: { type: 'boolean' },
        title: { type: 'text', analyzer: 'tr_analyzer' },
        description: { type: 'text', analyzer: 'tr_analyzer' },
        created_at: { type: 'date' },
        updated_at: { type: 'date' },
      },
    },
  });
}

async function recreateIndex() {
  const existsResponse = await es.indices.exists({ index: ELASTICSEARCH_INDEX });
  const existsBody = unwrapEsResponse(existsResponse);
  const exists = typeof existsBody === 'boolean' ? existsBody : Boolean(existsBody);
  if (exists) {
    await es.indices.delete({ index: ELASTICSEARCH_INDEX });
  }
  await ensureIndexExists();
}

async function indexTodoById(todoId, ownerUsername) {
  const result = await pool.query(
    `SELECT id, title, description, completed, owner_username, created_at, updated_at
     FROM todos
     WHERE id = $1 AND owner_username = $2 AND deleted_at IS NULL`,
    [todoId, ownerUsername]
  );

  if (result.rows.length === 0) {
    // If it's deleted or missing, ensure ES doesn't keep stale data.
    await es.delete({ index: ELASTICSEARCH_INDEX, id: String(todoId) }, { ignore: [404] });
    return;
  }

  const row = result.rows[0];
  await es.index({
    index: ELASTICSEARCH_INDEX,
    id: String(row.id),
    document: rowToTodoDocument(row),
  });
}

async function deleteTodoFromIndex(todoId) {
  await es.delete({ index: ELASTICSEARCH_INDEX, id: String(todoId) }, { ignore: [404] });
}

async function syncFromDb() {
  await ensureIndexExists();

  if (SEARCH_SYNC_MODE === 'full') {
    await recreateIndex();
  }

  let lastId = 0;
  while (true) {
    const batch = await pool.query(
      `SELECT id, title, description, completed, owner_username, created_at, updated_at
       FROM todos
       WHERE deleted_at IS NULL AND id > $1
       ORDER BY id ASC
       LIMIT $2`,
      [lastId, SEARCH_DB_SYNC_BATCH_SIZE]
    );

    if (batch.rows.length === 0) {
      break;
    }

    const operations = [];
    for (const row of batch.rows) {
      lastId = Number(row.id);
      operations.push({ index: { _index: ELASTICSEARCH_INDEX, _id: String(row.id) } });
      operations.push(rowToTodoDocument(row));
    }

    const bulkResponse = unwrapEsResponse(await es.bulk({ operations }));
    if (bulkResponse.errors) {
      const firstError = (bulkResponse.items || []).find((item) => {
        const action = item.index || item.create || item.update || item.delete;
        return action && action.error;
      });
      console.error('Elasticsearch bulk sync had errors. Example:', firstError);
    }
  }
}

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

  try {
    await ensureIndexExists();

    const filter = [{ term: { owner_username: username } }];
    if (completed !== undefined) {
      filter.push({ term: { completed: completed === 'true' } });
    }

    const trimmedQuery = typeof q === 'string' ? q.trim() : '';
    const must = [];
    if (trimmedQuery) {
      must.push({
        multi_match: {
          query: trimmedQuery,
          fields: ['title^2', 'description'],
          operator: 'and',
          fuzziness: 'AUTO',
        },
      });
    }

    const esResponse = unwrapEsResponse(await es.search({
      index: ELASTICSEARCH_INDEX,
      from: offset,
      size: limit,
      track_total_hits: true,
      query: {
        bool: {
          filter,
          ...(must.length > 0 ? { must } : {}),
        },
      },
      sort: [{ created_at: { order: 'desc' } }],
    }));

    const total = esResponse.hits?.total?.value ?? 0;
    const data = (esResponse.hits?.hits || []).map((hit) => {
      const src = hit._source || {};
      return {
        id: src.id,
        title: src.title,
        description: src.description ?? null,
        completed: src.completed,
        created_at: src.created_at,
        updated_at: src.updated_at,
      };
    });

    res.json({
      data,
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

// Admin: full reindex (optional)
app.post('/api/search/admin/reindex', async (req, res) => {
  const adminToken = process.env.SEARCH_ADMIN_TOKEN;
  if (adminToken) {
    const provided = req.header('x-search-admin-token');
    if (provided !== adminToken) {
      return res.status(403).json({ error: 'Forbidden' });
    }
  }

  try {
    await syncFromDb();
    res.json({ status: 'OK' });
  } catch (err) {
    console.error('Reindex error:', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// Health check
app.get('/api/search/health', (req, res) => {
  res.json({
    status: 'UP',
    service: 'search-service',
    dependencies: {
      elasticsearch: ELASTICSEARCH_URL,
      kafka: kafkaConnected ? 'CONNECTED' : (KAFKA_ENABLED ? 'DISCONNECTED' : 'DISABLED'),
    },
  });
});

const PORT = process.env.SERVER_PORT || 8084;

async function startKafkaConsumer() {
  if (!KAFKA_ENABLED) {
    return;
  }

  const brokers = KAFKA_BOOTSTRAP_SERVERS.split(',').map((s) => s.trim()).filter(Boolean);
  const kafka = new Kafka({ clientId: 'search-service', brokers });
  kafkaConsumer = kafka.consumer({ groupId: KAFKA_GROUP_ID });

  await kafkaConsumer.connect();
  kafkaConnected = true;
  await kafkaConsumer.subscribe({ topic: TODO_EVENTS_TOPIC, fromBeginning: true });

  await kafkaConsumer.run({
    eachMessage: async ({ message }) => {
      if (!message.value) {
        return;
      }

      let event;
      try {
        event = JSON.parse(message.value.toString('utf8'));
      } catch (err) {
        console.error('Kafka message JSON parse error:', err);
        return;
      }

      const action = String(event.action || '').toUpperCase();
      const todoId = event.todoId;
      const owner = event.owner;
      if (!todoId || !owner) {
        return;
      }

      try {
        await ensureIndexExists();
        if (action === 'DELETED') {
          await deleteTodoFromIndex(todoId);
        } else if (action === 'CREATED' || action === 'UPDATED') {
          await indexTodoById(todoId, owner);
        }
      } catch (err) {
        console.error('Kafka -> Elasticsearch sync error:', err);
      }
    },
  });
}

app.listen(PORT, async () => {
  console.log(`Search service running on port ${PORT}`);

  try {
    await ensureIndexExists();
  } catch (err) {
    console.error('Elasticsearch connection/index init error:', err);
  }

  if (SEARCH_SYNC_ON_STARTUP) {
    syncFromDb()
      .then(() => console.log('Initial Elasticsearch sync completed.'))
      .catch((err) => console.error('Initial Elasticsearch sync failed:', err));
  }

  startKafkaConsumer().catch((err) => {
    kafkaConnected = false;
    console.error('Kafka consumer failed:', err);
  });
});
