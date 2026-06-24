use std::{future::Future, pin::Pin};

pub type TransactionFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

pub trait TransactionManager {
    type Transaction<'tx>
    where
        Self: 'tx;

    type Error;

    fn run_in_transaction<'a, T, F>(
        &'a self,
        f: F,
    ) -> TransactionFuture<'a, Result<T, Self::Error>>
    where
        T: Send + 'a,
        F: for<'tx> FnOnce(
                &'tx mut Self::Transaction<'tx>,
            ) -> TransactionFuture<'tx, Result<T, Self::Error>>
            + Send
            + 'a;
}
