use std::sync::Arc;

use crate::infrastructure::{database::DbPool, transaction::SqlxTransactionManager};

#[derive(Clone)]
pub struct AppState {
    pub db_pool: DbPool,
    pub transaction_manager: Arc<SqlxTransactionManager>,
}

impl AppState {
    pub fn new(db_pool: DbPool) -> Self {
        Self {
            transaction_manager: Arc::new(SqlxTransactionManager::new(db_pool.clone())),
            db_pool,
        }
    }
}
