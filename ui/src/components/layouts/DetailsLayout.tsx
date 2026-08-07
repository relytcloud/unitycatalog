import { Breadcrumb, Col, Flex, Grid, Row } from 'antd';
import { BreadcrumbItemType } from 'antd/es/breadcrumb/Breadcrumb';
import React, { ReactNode } from 'react';
import { getChildOnType } from '../../utils/getChildOnType';
import ResizableSplit from './ResizableSplit';

interface DetailsLayoutProps {
  title: ReactNode;
  breadcrumbs?: BreadcrumbItemType[];
  children: ReactNode;
}

const { useBreakpoint } = Grid;

function DetailsLayout({ title, breadcrumbs, children }: DetailsLayoutProps) {
  const screens = useBreakpoint();

  const contentChild = getChildOnType(children, Content);
  const asideChild = getChildOnType(children, Aside);

  return (
    <Flex vertical gap="middle" style={{ flexGrow: 1, minWidth: 0 }}>
      <Row>
        <Col span={24}>{breadcrumbs && <Breadcrumb items={breadcrumbs} />}</Col>
        <Col span={24}>{title}</Col>
      </Row>
      {screens.lg ? (
        // Wide screens: content | draggable divider | fixed-width sidebar.
        <ResizableSplit
          fixed="right"
          defaultSize={360}
          minSize={240}
          maxSize={640}
          storageKey="uc-ui-split-details-aside"
          style={{ borderTop: '1px solid lightgrey' }}
          left={
            <div style={{ paddingTop: 16, paddingRight: 12, minWidth: 0 }}>
              {contentChild}
            </div>
          }
          right={
            <div style={{ paddingTop: 16, paddingLeft: 12 }}>{asideChild}</div>
          }
        />
      ) : (
        // Narrow screens keep the stacked layout (sidebar above content).
        <Flex
          vertical
          style={{ borderTop: '1px solid lightgrey', flexGrow: 1 }}
        >
          <div style={{ paddingTop: 16 }}>{asideChild}</div>
          <div style={{ paddingTop: 16 }}>{contentChild}</div>
        </Flex>
      )}
    </Flex>
  );
}

function Content({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

DetailsLayout.Content = Content;

function Aside({ children }: { children: ReactNode }) {
  return <>{children}</>;
}

DetailsLayout.Aside = Aside;

export default DetailsLayout;
