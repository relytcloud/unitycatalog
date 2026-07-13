import { SearchOutlined } from '@ant-design/icons';
import {
  Alert,
  Col,
  Flex,
  Input,
  Row,
  Table,
  TableProps,
  TableColumnsType,
} from 'antd';
import { AnyObject } from 'antd/es/_util/type';
import { ReactNode, useMemo, useState } from 'react';
import styles from './ListLayout.module.css';

// Page-size options shown by the table's size changer, with 20 as the default.
export const PAGE_SIZE_OPTIONS = [20, 50, 100];
const DEFAULT_PAGE_SIZE = 20;

interface ListLayoutProps<T> {
  data: T[] | undefined;
  columns: TableColumnsType<T>;
  title: ReactNode;
  rowKey: TableProps['rowKey'];
  onRowClick?: (record: T) => void;
  loading?: boolean;
  filters?: ReactNode;
  showSearch?: boolean;
  // Error from the data query; when set, an alert replaces the table so a
  // failed load is never shown as an empty ("no data") list.
  error?: Error | null;
  // Text a row is matched against by the search box. Defaults to the row's
  // name; pass a custom accessor to also search other fields (e.g. email).
  searchText?: (item: T) => string;
}

export default function ListLayout<T extends AnyObject = AnyObject>({
  data,
  columns,
  title,
  rowKey,
  onRowClick,
  loading,
  filters,
  showSearch = true,
  error,
  searchText,
}: ListLayoutProps<T>) {
  const [filterValue, setFilterValue] = useState('');
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);

  const filteredData = useMemo(() => {
    if (!filterValue) return data;
    const needle = filterValue.toLowerCase();
    return data?.filter((item) =>
      (searchText ? searchText(item) : String(item.name))
        .toLowerCase()
        .includes(needle),
    );
  }, [data, filterValue, searchText]);

  const onShowSizeChange = (current: number, pageSize: number) => {
    setPageSize(pageSize);
  };

  return (
    <Flex gap="middle" vertical style={{ flexGrow: 1 }}>
      {title}
      {error && (
        <Alert
          type="error"
          showIcon
          message="Failed to load"
          description={error.message}
        />
      )}
      {showSearch && (
        <Row gutter={[8, 8]}>
          <Col
            span={8}
            xs={{ span: 12 }}
            md={{ span: 10 }}
            lg={{ span: 8 }}
            xl={{ span: 6 }}
          >
            <Input
              placeholder="Search"
              prefix={<SearchOutlined />}
              value={filterValue}
              onChange={(e) => setFilterValue(e.target.value)}
            />
          </Col>
          {filters && <Col flex={1}>{filters}</Col>}
        </Row>
      )}
      <Table
        rowKey={rowKey}
        loading={loading}
        className={onRowClick ? styles.clickableListLayout : undefined}
        dataSource={error ? [] : filteredData}
        columns={columns}
        pagination={{
          pageSize: pageSize,
          showSizeChanger: true,
          pageSizeOptions: PAGE_SIZE_OPTIONS,
          onShowSizeChange: onShowSizeChange,
          showTotal: (total, range) => `${range[0]}-${range[1]} of ${total}`,
        }}
        onRow={(row) => {
          return {
            onClick: () => onRowClick?.(row),
          };
        }}
      />
    </Flex>
  );
}
