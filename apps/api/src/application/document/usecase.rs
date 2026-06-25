use std::sync::Arc;

use crate::{
    application::{
        document::repository::DocumentRepository,
        transaction::{TransactionFuture, TransactionManager},
    },
    domain::document::document::Document,
};

#[derive(Debug, Clone)]
pub struct CreateDocumentInput {
    pub title: String,
    pub latest_read_line: i32,
    pub version: i16,
    pub document_url: String,
}

#[derive(Debug, Clone)]
pub struct CreateDocumentOutput {
    pub id: uuid::Uuid,
}

#[derive(Clone)]
pub struct CreateDocumentUseCase<R, TM> {
    document_repository: Arc<R>,
    transaction_manager: Arc<TM>,
}

impl<R, TM> CreateDocumentUseCase<R, TM> {
    pub fn new(document_repository: Arc<R>, transaction_manager: Arc<TM>) -> Self {
        Self {
            document_repository,
            transaction_manager,
        }
    }
}

impl<R, TM> CreateDocumentUseCase<R, TM>
where
    TM: TransactionManager + Send + Sync,
    R: Send + Sync + 'static,
    for<'tx> TM::Transaction<'tx>: Send,
    for<'tx> R: DocumentRepository<TM::Transaction<'tx>, Error = TM::Error>,
{
    pub fn execute<'a>(
        &'a self,
        input: CreateDocumentInput,
    ) -> TransactionFuture<'a, Result<CreateDocumentOutput, TM::Error>>
    where
        TM::Error: Send + 'a,
    {
        Box::pin(async move {
            let document = Document::new(
                None,
                input.title,
                input.latest_read_line,
                input.version,
                input.document_url,
            );

            let document_id = document.id.0;
            let repository = self.document_repository.clone();

            self.transaction_manager
                .run_in_transaction(|tx| {
                    Box::pin(async move {
                        repository.save(tx, &document).await?;
                        Ok(CreateDocumentOutput { id: document_id })
                    })
                })
                .await
        })
    }
}
