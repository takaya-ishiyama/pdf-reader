use std::sync::Arc;

use crate::{
    application::document::usecase::CreateDocumentUseCase,
    infrastructure::{
        database::DbPool, document_repository::SqlxDocumentRepository,
        transaction::SqlxTransactionManager,
    },
};

pub type CreateDocumentUseCaseImpl =
    CreateDocumentUseCase<SqlxDocumentRepository, SqlxTransactionManager>;

#[derive(Clone)]
pub struct AppState {
    pub db_pool: DbPool,
    pub transaction_manager: Arc<SqlxTransactionManager>,
    pub repositories: Repositories,
    pub usecases: UseCases,
}

#[derive(Clone)]
pub struct Repositories {
    pub document_repository: Arc<SqlxDocumentRepository>,
}

#[derive(Clone)]
pub struct UseCases {
    pub create_document: Arc<CreateDocumentUseCaseImpl>,
}

impl AppState {
    pub fn new(db_pool: DbPool) -> Self {
        let transaction_manager = Arc::new(SqlxTransactionManager::new(db_pool.clone()));
        let document_repository = Arc::new(SqlxDocumentRepository::new());
        let repositories = Repositories {
            document_repository: document_repository.clone(),
        };
        let usecases = UseCases {
            create_document: Arc::new(CreateDocumentUseCase::new(
                document_repository,
                transaction_manager.clone(),
            )),
        };

        Self {
            db_pool,
            transaction_manager,
            repositories,
            usecases,
        }
    }
}
