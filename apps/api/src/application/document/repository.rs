use crate::{
    application::transaction::TransactionFuture,
    domain::document::document::{Document, DocumentId},
};

pub trait DocumentRepository<Tx> {
    type Error;

    fn find_by_id<'a>(
        &'a self,
        tx: &'a mut Tx,
        id: DocumentId,
    ) -> TransactionFuture<'a, Result<Option<Document>, Self::Error>>;

    fn save<'a>(
        &'a self,
        tx: &'a mut Tx,
        document: &'a Document,
    ) -> TransactionFuture<'a, Result<(), Self::Error>>;
}
