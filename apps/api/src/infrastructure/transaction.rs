use sqlx::PgConnection;

use crate::{
    application::transaction::{TransactionFuture, TransactionManager},
    infrastructure::database::DbPool,
};

#[derive(Clone)]
pub struct SqlxTransactionManager {
    db_pool: DbPool,
}

impl SqlxTransactionManager {
    pub fn new(db_pool: DbPool) -> Self {
        Self { db_pool }
    }
}

impl TransactionManager for SqlxTransactionManager {
    type Transaction<'tx> = PgConnection;
    type Error = sqlx::Error;

    fn run_in_transaction<'a, T, F>(&'a self, f: F) -> TransactionFuture<'a, Result<T, Self::Error>>
    where
        T: Send + 'a,
        F: for<'tx> FnOnce(
                &'tx mut Self::Transaction<'tx>,
            ) -> TransactionFuture<'tx, Result<T, Self::Error>>
            + Send
            + 'a,
    {
        Box::pin(async move {
            let mut tx = self.db_pool.begin().await?;
            let result = f(&mut tx).await;

            match result {
                Ok(value) => {
                    tx.commit().await?;
                    Ok(value)
                }
                Err(err) => {
                    tx.rollback().await?;
                    Err(err)
                }
            }
        })
    }
}
